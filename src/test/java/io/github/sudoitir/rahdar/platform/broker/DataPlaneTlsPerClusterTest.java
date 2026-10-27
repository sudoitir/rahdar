package io.github.sudoitir.rahdar.platform.broker;

import static io.github.sudoitir.rahdar.support.ArtemisIntegrationTest.BROKER_PASSWORD;
import static io.github.sudoitir.rahdar.support.ArtemisIntegrationTest.BROKER_USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.sudoitir.openbroker.BrokerDrivers;
import io.github.sudoitir.rahdar.support.ArtemisIntegrationTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.ssl.DefaultSslBundleRegistry;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.pem.PemSslStoreBundle;
import org.springframework.boot.ssl.pem.PemSslStoreDetails;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * Two brokers whose certificates chain to two different authorities, reached over Core at the same
 * time, each with its own cluster's TLS bundle (ADR-0098, core-transport spec). The authorities are
 * generated with {@code keytool} for each run, so no key material is checked in.
 */
class DataPlaneTlsPerClusterTest {

    private static final String STORE_PASSWORD = "changeit";
    private static final String OVERRIDE = "/var/lib/artemis-instance/etc-override/";

    @TempDir
    static Path dir;

    private static final List<GenericContainer<?>> brokers = new ArrayList<>();
    private static GenericContainer<?> brokerA;
    private static GenericContainer<?> brokerB;
    private static DataPlaneCheck check;

    @BeforeAll
    static void start() throws Exception {
        Path xml = dir.resolve("broker.xml");
        Files.writeString(
                xml,
                Files.readString(Path.of("deploy/compose/dev/artemis/primary/broker.xml"))
                        .replace(
                                "tcp://0.0.0.0:61616?",
                                "tcp://0.0.0.0:61616?sslEnabled=true;keyStoreType=PKCS12;keyStorePath=" + OVERRIDE
                                        + "server.p12;keyStorePassword=" + STORE_PASSWORD + ";"));
        String caA = authorityWithServer("a");
        String caB = authorityWithServer("b");
        brokerA = broker(xml, dir.resolve("a/server.p12"));
        brokerB = broker(xml, dir.resolve("b/server.p12"));

        DefaultSslBundleRegistry bundles = new DefaultSslBundleRegistry();
        bundles.registerBundle("ca-a", trusting(caA));
        bundles.registerBundle("ca-b", trusting(caB));
        check = new DataPlaneCheck(new BrokerGateway(
                BrokerDrivers.load(),
                mock(NodeDirectory.class),
                mock(ConnectionSettingsSource.class),
                new BrokerTls(bundles),
                mock(RahdarCallInterceptor.class),
                new BrokerTimeouts(new BrokerProperties(Duration.ofSeconds(5), Duration.ofSeconds(10), 2_000)),
                BrokerTelemetry.none()));
    }

    @AfterAll
    static void stop() {
        brokers.forEach(GenericContainer::stop);
    }

    @Test
    void bothClustersConnectAtOnceEachTrustingOnlyItsOwnAuthority() {
        assertThat(check(brokerA, "ca-a").accounts()).containsExactly(AccountResult.ACCEPTED);
        assertThat(check(brokerB, "ca-b").accounts()).containsExactly(AccountResult.ACCEPTED);
        // A connection opened after the other cluster's must still trust its own authority.
        assertThat(check(brokerA, "ca-a").accounts()).containsExactly(AccountResult.ACCEPTED);

        DataPlaneCheck.Result wrong = check(brokerB, "ca-a");
        assertThat(wrong.accounts())
                .as("broker B's certificate is not signed by authority A")
                .containsExactly(AccountResult.UNREACHABLE);
        // The client reports a refused handshake as a failed connect, without the TLS cause.
        assertThat(wrong.verdict()).isInstanceOf(SubscriptionVerdict.Failed.class);
    }

    @Test
    void anUndefinedBundleFailsOnlyItsOwnClusterAndNamesTheBundle() {
        assertThat(check(brokerB, "no-such-bundle").verdict())
                .isInstanceOfSatisfying(
                        SubscriptionVerdict.Failed.class,
                        f -> assertThat(f.reason()).contains("no-such-bundle"));
        assertThat(check(brokerA, "ca-a").accounts()).containsExactly(AccountResult.ACCEPTED);
    }

    private static DataPlaneCheck.Result check(GenericContainer<?> broker, String bundle) {
        String url = "tcp://%s:%d".formatted(broker.getHost(), broker.getMappedPort(61616));
        NodeEndpoint node = new NodeEndpoint(
                UUID.randomUUID(),
                "n",
                "n",
                null,
                url,
                "PRIMARY",
                "STARTED",
                true,
                null,
                1L,
                "2.44.0",
                null,
                null,
                Instant.now(),
                null,
                null,
                false,
                true);
        return check.check(
                List.of(node),
                new CoreConnectionSettings(UUID.randomUUID(), BROKER_USER, BROKER_PASSWORD, bundle, false));
    }

    private static SslBundle trusting(String caPem) {
        return SslBundle.of(new PemSslStoreBundle(null, PemSslStoreDetails.forCertificate(caPem)));
    }

    private static GenericContainer<?> broker(Path xml, Path keystore) {
        GenericContainer<?> broker = new GenericContainer<>(ArtemisIntegrationTest.IMAGE)
                .withEnv("ARTEMIS_USER", BROKER_USER)
                .withEnv("ARTEMIS_PASSWORD", BROKER_PASSWORD)
                .withEnv("ANONYMOUS_LOGIN", "false")
                .withCopyFileToContainer(MountableFile.forHostPath(xml, 0644), OVERRIDE + "broker.xml")
                .withCopyFileToContainer(MountableFile.forHostPath(keystore, 0644), OVERRIDE + "server.p12")
                .withExposedPorts(61616)
                .waitingFor(Wait.forLogMessage(".*Artemis Console available.*", 1)
                        .withStartupTimeout(Duration.ofSeconds(120)));
        brokers.add(broker);
        broker.start();
        return broker;
    }

    /** A fresh authority, and a server key pair it signed, in {@code <name>/server.p12}; returns the authority's PEM. */
    private static String authorityWithServer(String name) throws Exception {
        Path d = Files.createDirectories(dir.resolve(name));
        String ca = d.resolve("ca.p12").toString();
        String server = d.resolve("server.p12").toString();
        keytool(
                "-genkeypair",
                "-alias",
                "ca",
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-validity",
                "2",
                "-dname",
                "CN=Rahdar test CA " + name,
                "-ext",
                "bc:c",
                "-storetype",
                "PKCS12",
                "-keystore",
                ca,
                "-storepass",
                STORE_PASSWORD);
        keytool(
                "-exportcert",
                "-rfc",
                "-alias",
                "ca",
                "-keystore",
                ca,
                "-storepass",
                STORE_PASSWORD,
                "-file",
                d.resolve("ca.pem").toString());
        keytool(
                "-genkeypair",
                "-alias",
                "server",
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-validity",
                "2",
                "-dname",
                "CN=localhost",
                "-storetype",
                "PKCS12",
                "-keystore",
                server,
                "-storepass",
                STORE_PASSWORD);
        keytool(
                "-certreq",
                "-alias",
                "server",
                "-keystore",
                server,
                "-storepass",
                STORE_PASSWORD,
                "-file",
                d.resolve("server.csr").toString());
        keytool(
                "-gencert",
                "-rfc",
                "-alias",
                "ca",
                "-keystore",
                ca,
                "-storepass",
                STORE_PASSWORD,
                "-infile",
                d.resolve("server.csr").toString(),
                "-outfile",
                d.resolve("server.pem").toString(),
                "-ext",
                "san=dns:localhost,ip:127.0.0.1",
                "-validity",
                "2");
        keytool(
                "-importcert",
                "-noprompt",
                "-alias",
                "ca",
                "-file",
                d.resolve("ca.pem").toString(),
                "-keystore",
                server,
                "-storepass",
                STORE_PASSWORD);
        keytool(
                "-importcert",
                "-alias",
                "server",
                "-file",
                d.resolve("server.pem").toString(),
                "-keystore",
                server,
                "-storepass",
                STORE_PASSWORD);
        return Files.readString(d.resolve("ca.pem"));
    }

    private static void keytool(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new IllegalStateException("keytool " + args[0] + " failed: " + output);
        }
    }
}
