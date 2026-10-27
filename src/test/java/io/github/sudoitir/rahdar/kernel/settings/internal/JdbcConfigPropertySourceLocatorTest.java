package io.github.sudoitir.rahdar.kernel.settings.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

/**
 * The bootstrap-phase loader: what it reads, how profiles layer, and — the part
 * that matters most — that it never stops the application from starting.
 */
@ExtendWith(OutputCaptureExtension.class)
class JdbcConfigPropertySourceLocatorTest extends PostgresIntegrationTest {

    private final JdbcConfigPropertySourceLocator locator = new JdbcConfigPropertySourceLocator();

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ConfigurableEnvironment springEnvironment;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM rahdar_config_property");
    }

    private MockEnvironment environment(String... activeProfiles) {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.application.name", "rahdar");
        env.setProperty("spring.datasource.url", springEnvironment.getProperty("spring.datasource.url"));
        env.setProperty("spring.datasource.username", springEnvironment.getProperty("spring.datasource.username"));
        env.setProperty("spring.datasource.password", springEnvironment.getProperty("spring.datasource.password"));
        env.setActiveProfiles(activeProfiles);
        return env;
    }

    private void row(String profile, String key, String value) {
        jdbc.update(
                "INSERT INTO rahdar_config_property (application, profile, label, \"key\", \"value\") "
                        + "VALUES (?, ?, 'master', ?, ?)",
                "rahdar",
                profile,
                key,
                value);
    }

    @Test
    void loadsPropertiesForTheApplication() {
        row("default", "rahdar.branding.product-name", "Broker Console");

        PropertySource<?> source = locator.locate(environment());

        assertThat(source).isNotNull();
        assertThat(source.getProperty("rahdar.branding.product-name")).isEqualTo("Broker Console");
    }

    /** A profile row beats the shared one — that is the whole reason profiles are in the key. */
    @Test
    void aProfileRowOverridesTheDefaultRow() {
        row("default", "rahdar.scrape.tier-a-interval", "5s");
        row("prod", "rahdar.scrape.tier-a-interval", "30s");

        assertThat(locator.locate(environment("prod")).getProperty("rahdar.scrape.tier-a-interval"))
                .isEqualTo("30s");
        assertThat(locator.locate(environment()).getProperty("rahdar.scrape.tier-a-interval"))
                .isEqualTo("5s");
    }

    /**
     * Without these three flags Spring Cloud inserts a bootstrap source above
     * everything, environment variables included — so a row here would beat the
     * container's own settings and could not be corrected from outside the database.
     * They have to travel in the source itself, because
     * {@code PropertySourceBootstrapConfiguration} binds them from the incoming
     * remote source and not from local configuration; set in {@code bootstrap.yml}
     * they are read by nobody. Verified end to end: with them, an environment
     * variable beats a stored row, and a stored row still beats {@code application.yml}.
     */
    @Test
    void theSourceCarriesTheFlagsThatKeepItBelowTheEnvironment() {
        row("default", "rahdar.branding.product-name", "Broker Console");

        PropertySource<?> source = locator.locate(environment());

        assertThat(source.getProperty("spring.cloud.config.allowOverride")).isEqualTo("true");
        assertThat(source.getProperty("spring.cloud.config.overrideNone")).isEqualTo("false");
        assertThat(source.getProperty("spring.cloud.config.overrideSystemProperties"))
                .isEqualTo("false");
    }

    @Test
    void contributesNothingWhenThereAreNoRows() {
        assertThat(locator.locate(environment())).isNull();
    }

    /**
     * The one that keeps a deployment alive: on a first start the schema does not
     * exist yet, because Liquibase has not run and cannot run until the main context
     * is up. That is the normal first boot, so it is neither a failure nor a warning,
     * and the table is looked up before it is queried, so the database logs no error either.
     */
    @Test
    void aMissingTableIsAnOrdinaryFirstStart(CapturedOutput output) {
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS config_absent");
        MockEnvironment env = environment();
        String url = springEnvironment.getProperty("spring.datasource.url");
        env.setProperty("spring.datasource.url", url + (url.contains("?") ? "&" : "?") + "currentSchema=config_absent");

        assertThat(locator.locate(env)).isNull();
        assertThat(output)
                .contains("No rahdar_config_property table yet")
                .doesNotContain("relation \"rahdar_config_property\" does not exist");
    }

    /** An unreachable database is a warning, never a startup failure. */
    @Test
    void anUnreachableDatabaseIsAWarningAndNotAStartupFailure() {
        MockEnvironment env = environment();
        env.setProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:1/nope");

        assertThatCode(() -> assertThat(locator.locate(env)).isNull()).doesNotThrowAnyException();
    }

    /** No datasource configured at all is simply "nothing to load", not an error. */
    @Test
    void noDatasourceUrlContributesNothing() {
        MockEnvironment env = new MockEnvironment();

        assertThat(locator.locate(env)).isNull();
    }
}
