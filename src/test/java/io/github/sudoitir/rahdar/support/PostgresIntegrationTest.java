package io.github.sudoitir.rahdar.support;

import io.github.sudoitir.rahdar.platform.clusters.ClusterOwnership;
import io.github.sudoitir.rahdar.platform.scrape.ScrapeScheduler;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for tests that need the full context against a real PostgreSQL: Liquibase
 * applies the changelog, Hibernate validates the mapping against it, and the
 * {@code SecretVault} key is supplied so the context starts.
 *
 * <p>The container is a process-wide singleton, started once and never stopped
 * (Ryuk cleans it up at JVM exit). This is the CI-safe Testcontainers pattern:
 * Spring caches contexts across test classes, so a per-class {@code @Container}
 * that stops after each class leaves a later class's cached context pointing at a
 * dead database.
 */
@SpringBootTest
public abstract class PostgresIntegrationTest {

    /**
     * The scrape tiers would otherwise run inside the shared context and call whichever
     * {@code BrokerConnections} a test has mocked, consuming the canned Jolokia
     * responses that test queued for its own requests. The mock's tier methods do nothing, so the
     * runner's tier jobs run and change nothing; no test relies on background scraping, and
     * {@code ScrapeSchedulerTest} drives the tiers directly.
     */
    @MockitoBean
    ScrapeScheduler scrapeScheduler;

    /** Owns every cluster, from the moment it is made (see {@link OwnsEveryCluster}). */
    @TestBean(methodName = "io.github.sudoitir.rahdar.support.OwnsEveryCluster#clusterOwnership")
    protected ClusterOwnership clusterOwnership;

    /**
     * Spring keeps every distinct test context cached, each with its own connection pool, so the
     * default 100 connections run out once enough test configurations exist.
     */
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18-alpine").withCommand("postgres", "-c", "max_connections=400");

    /** Base64 of exactly 32 bytes. */
    private static final String SECRET_KEY = "YXJ0ZW1pcy1zdHVkaW8tdGVzdC1rZXktMzJieXRlcyE=";

    static {
        POSTGRES.withReuse(true).start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("rahdar.secret-key", () -> SECRET_KEY);
        // A cached context closes with the JVM; it has no load balancer to wait for.
        registry.add("rahdar.ha.drain-delay", () -> "0s");
        registry.add("rahdar.ha.run-grace", () -> "0s");
        // For the same reason as the scrape tiers: the plugin-messaging pass would visit every
        // cluster the shared database has accumulated, from a scheduler thread, and call a mocked
        // BrokerConnections while a test is stubbing it. Its startup sweep still runs, before any
        // test; tests that need a pass drive the reconciler themselves.
        registry.add("rahdar.plugins.messaging.reconcile-interval", () -> "1h");
    }

    /**
     * Rows dated in the past need partitions, which a purge in an earlier test may have dropped (ADR-0193).
     *
     * <p>Failed sign-ins from one address add up in this shared database, and thirty of them in ten minutes lock
     * that address for every later test (ADR-0144). A success does not clear the counter. Loopback is the address
     * of every client that does not pick its own, so each test starts with the source counters cleared. A test
     * that throttles an address still does so inside one method.
     */
    @BeforeEach
    void prepareSharedDatabase() {
        // Its own connection: some subclasses start the application themselves, outside the Spring test framework.
        JdbcTemplate jdbc = new JdbcTemplate(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        TestPartitions.ensureBack(jdbc);
        jdbc.update("DELETE FROM login_attempt WHERE starts_with(key, 's:')");
    }

    /** What an application started outside the Spring test framework needs to use the shared database. */
    public static List<String> connectionProperties() {
        return List.of(
                "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "spring.datasource.username=" + POSTGRES.getUsername(),
                "spring.datasource.password=" + POSTGRES.getPassword(),
                "rahdar.secret-key=" + SECRET_KEY,
                "rahdar.ha.drain-delay=0s");
    }
}
