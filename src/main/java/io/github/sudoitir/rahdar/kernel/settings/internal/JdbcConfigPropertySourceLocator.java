package io.github.sudoitir.rahdar.kernel.settings.internal;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.bootstrap.config.PropertySourceLocator;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

/**
 * Loads deploy-time properties out of {@code rahdar_config_property} during the
 * bootstrap phase, so a row in Postgres can set anything the application reads
 * while it is starting (ADR-0047).
 *
 * <p>This runs in the bootstrap context, before the main context and therefore
 * before the {@code DataSource}, JPA, Liquibase or anything else exists. It opens
 * its own short-lived JDBC connection from the bootstrap {@code Environment} and
 * closes it immediately — deliberately plain {@code DriverManager} rather than a
 * pool, because a pool built here would outlive its usefulness by the length of
 * the process.
 *
 * <p><b>Never fatal.</b> A missing table (first boot, before Liquibase has run) contributes
 * no properties; it is looked up before it is queried, so a first start logs no error here or
 * in the database. An unreachable database or bad credentials log a warning and contribute none
 * either. The alternative — refusing to start — would mean an empty database
 * could brick the very deployment that is trying to create its schema, and would
 * turn an optional configuration source into a hard startup dependency.
 */
public class JdbcConfigPropertySourceLocator implements PropertySourceLocator {

    private static final Logger log = LoggerFactory.getLogger(JdbcConfigPropertySourceLocator.class);

    private static final String TABLE_EXISTS = "SELECT to_regclass('rahdar_config_property') IS NOT NULL";

    private static final String SELECT =
            "SELECT \"key\", \"value\" FROM rahdar_config_property WHERE application = ? AND profile = ? AND label = ?";

    /** Matches Spring Cloud Config's own default, so rows are portable to a real config server. */
    private static final String DEFAULT_LABEL = "master";

    private static final String DEFAULT_PROFILE = "default";

    @Override
    public PropertySource<?> locate(Environment environment) {
        String url = environment.getProperty("spring.datasource.url");
        if (url == null || url.isBlank()) {
            return null;
        }
        String application = environment.getProperty("spring.application.name", "application");
        String label = environment.getProperty("rahdar.config.label", DEFAULT_LABEL);
        String[] profiles = environment.getActiveProfiles();

        Map<String, Object> properties = new LinkedHashMap<>();
        try (Connection connection = DriverManager.getConnection(
                url,
                environment.getProperty("spring.datasource.username"),
                environment.getProperty("spring.datasource.password"))) {
            if (!tableExists(connection)) {
                log.info("No rahdar_config_property table yet (a first start): packaged defaults apply");
                return null;
            }
            // Least specific first so a profile-specific row overwrites the shared one.
            load(connection, properties, application, DEFAULT_PROFILE, label);
            for (String profile : profiles) {
                load(connection, properties, application, profile, label);
            }
        } catch (Exception e) {
            log.warn(
                    "No deploy-time properties loaded from rahdar_config_property ({}). Packaged defaults apply.",
                    e.getMessage());
            return null;
        }

        if (properties.isEmpty()) {
            return null;
        }
        int rowCount = properties.size();

        // Precedence. Spring Cloud's default inserts a bootstrap source ABOVE
        // everything, environment variables included — so a row here would beat the
        // container's own -e settings, and a bad row could not be corrected from
        // outside the database. These three flags move it to just below the
        // environment instead: packaged defaults < rahdar_config_property <
        // environment, so a deployment always has the last word over its own database.
        //
        // They are set here, in the returned source, and NOT in bootstrap.yml, because
        // PropertySourceBootstrapConfiguration binds them from the incoming remote
        // source rather than from local configuration. In bootstrap.yml they are read
        // by nobody and silently do nothing.
        properties.putIfAbsent("spring.cloud.config.allowOverride", "true");
        properties.putIfAbsent("spring.cloud.config.overrideNone", "false");
        properties.putIfAbsent("spring.cloud.config.overrideSystemProperties", "false");
        log.info("Loaded {} deploy-time properties from rahdar_config_property", rowCount);
        return new MapPropertySource("rahdarConfigProperty", properties);
    }

    private static boolean tableExists(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(TABLE_EXISTS);
                ResultSet result = statement.executeQuery()) {
            return result.next() && result.getBoolean(1);
        }
    }

    private static void load(
            Connection connection, Map<String, Object> into, String application, String profile, String label)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT)) {
            statement.setString(1, application);
            statement.setString(2, profile);
            statement.setString(3, label);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    into.put(rows.getString(1), rows.getString(2));
                }
            }
        }
    }
}
