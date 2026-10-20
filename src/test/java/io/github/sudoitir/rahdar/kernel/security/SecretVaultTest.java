package io.github.sudoitir.rahdar.kernel.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class SecretVaultTest {

    private static final String AAD = "row|1";

    private static SecretKey key(int fill) {
        byte[] k = new byte[32];
        Arrays.fill(k, (byte) fill);
        return new SecretKeySpec(k, "AES");
    }

    /** A provider whose keyring can grow, counting how often it is loaded. */
    private static final class FakeProvider implements KeyProvider {
        final TreeMap<Integer, SecretKey> keys = new TreeMap<>();
        final AtomicInteger loads = new AtomicInteger();

        FakeProvider(int... versions) {
            for (int v : versions) {
                keys.put(v, key(v));
            }
        }

        @Override
        public Keyring load() {
            loads.incrementAndGet();
            return new Keyring(keys);
        }

        @Override
        public Optional<String> secret(String name) {
            return Optional.empty();
        }

        @Override
        public String name() {
            return "fake";
        }
    }

    /** A clock the test moves. */
    private static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** The stored current version, without a database: {@code stored} of 0 means the highest version. */
    private static SecretKeyState stored(int stored) {
        return new SecretKeyState(null) {
            @Override
            public int currentOrInit(int initial) {
                return stored == 0 ? initial : stored;
            }
        };
    }

    private static SecretVault vault(FakeProvider provider) {
        return vault(provider, stored(0), new TestClock());
    }

    private static SecretVault vault(FakeProvider provider, SecretKeyState state, Clock clock) {
        SecretVault vault = new SecretVault(provider, state, clock);
        vault.afterSingletonsInstantiated();
        return vault;
    }

    @Test
    void roundTrips() {
        SecretVault vault = vault(new FakeProvider(1));

        byte[] blob = vault.seal(AAD, "s3cr3t");

        assertThat(vault.open(AAD, blob)).isEqualTo("s3cr3t");
        assertThat(SecretVault.kekVersion(blob)).isEqualTo(1);
    }

    @Test
    void freshDataKeyAndNoncePerSeal() {
        SecretVault vault = vault(new FakeProvider(1));

        assertThat(vault.seal(AAD, "same")).isNotEqualTo(vault.seal(AAD, "same"));
    }

    @Test
    void aadBindsTheBlobToItsRow() {
        SecretVault vault = vault(new FakeProvider(1));
        UUID clusterA = UUID.randomUUID();
        byte[] blob = vault.seal(SecretVault.aad(clusterA, "MANAGEMENT"), "x");

        assertThatThrownBy(() -> vault.open(SecretVault.aad(UUID.randomUUID(), "MANAGEMENT"), blob))
                .isInstanceOf(SecretVault.SecretDecryptException.class);
        assertThatThrownBy(() -> vault.open(SecretVault.aad(clusterA, "CORE"), blob))
                .isInstanceOf(SecretVault.SecretDecryptException.class);
    }

    @Test
    void tamperingAnywhereIsRejected() {
        SecretVault vault = vault(new FakeProvider(1));
        byte[] blob = vault.seal(AAD, "x");

        // wrap nonce, wrapped DEK, nonce and ciphertext each fail authentication
        for (int at : new int[] {6, 30, 70, blob.length - 1}) {
            byte[] tampered = blob.clone();
            tampered[at] ^= 0x01;
            assertThatThrownBy(() -> vault.open(AAD, tampered))
                    .as("byte %d", at)
                    .isInstanceOf(SecretVault.SecretDecryptException.class);
        }
    }

    @Test
    void malformedBlobsAreRejected() {
        SecretVault vault = vault(new FakeProvider(1));
        byte[] blob = vault.seal(AAD, "x");
        byte[] wrongFormat = blob.clone();
        wrongFormat[0] = 2;

        assertThatThrownBy(() -> vault.open(AAD, new byte[10])).isInstanceOf(SecretVault.SecretDecryptException.class);
        assertThatThrownBy(() -> vault.open(AAD, null)).isInstanceOf(SecretVault.SecretDecryptException.class);
        assertThatThrownBy(() -> vault.open(AAD, wrongFormat)).isInstanceOf(SecretVault.SecretDecryptException.class);
        assertThatThrownBy(() -> SecretVault.kekVersion(new byte[3]))
                .isInstanceOf(SecretVault.SecretDecryptException.class);
    }

    @Test
    void aWrongKekIsRejected() {
        byte[] blob = vault(new FakeProvider(1)).seal(AAD, "x");
        FakeProvider other = new FakeProvider();
        other.keys.put(1, key(9));

        assertThatThrownBy(() -> vault(other).open(AAD, blob)).isInstanceOf(SecretVault.SecretDecryptException.class);
    }

    @Test
    void anUnknownVersionReloadsTheKeyringOnceThenNamesTheVersion() {
        FakeProvider provider = new FakeProvider(1, 2);
        byte[] blobV2 = sealUnder(provider, 2);
        FakeProvider stale = new FakeProvider(1);
        TestClock clock = new TestClock();
        SecretVault vault = vault(stale, stored(0), clock);
        int loads = stale.loads.get();

        assertThatThrownBy(() -> vault.open(AAD, blobV2))
                .isInstanceOf(SecretVault.SecretDecryptException.class)
                .hasMessageContaining("version 2");
        assertThat(stale.loads.get()).isEqualTo(loads + 1);

        // the provider gained the key: once the back-off has passed the same call opens the blob
        stale.keys.put(2, key(2));
        clock.now = clock.now.plus(SecretVault.RELOAD_BACKOFF).plusSeconds(1);
        assertThat(vault.open(AAD, blobV2)).isEqualTo("x");
    }

    @Test
    void aMissingVersionAsksTheProviderAtMostOncePerBackOff() {
        FakeProvider provider = new FakeProvider(1, 2);
        byte[] blobV2 = sealUnder(provider, 2);
        FakeProvider stale = new FakeProvider(1);
        TestClock clock = new TestClock();
        SecretVault vault = vault(stale, stored(0), clock);
        int loads = stale.loads.get();

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> vault.open(AAD, blobV2)).isInstanceOf(SecretVault.SecretDecryptException.class);
            assertThatThrownBy(() -> vault.rewrap(blobV2, 1)).isInstanceOf(SecretVault.SecretDecryptException.class);
        }
        assertThat(stale.loads.get()).isEqualTo(loads + 1);

        clock.now = clock.now.plus(SecretVault.RELOAD_BACKOFF).plusSeconds(1);
        assertThatThrownBy(() -> vault.open(AAD, blobV2)).isInstanceOf(SecretVault.SecretDecryptException.class);
        assertThat(stale.loads.get()).isEqualTo(loads + 2);
    }

    @Test
    void sealingConfirmsAStaleCurrentVersionItselfAndUsesTheNewOne() {
        TestClock clock = new TestClock();
        int[] stored = {1};
        SecretVault vault = vault(new FakeProvider(1, 2), storedVersion(stored), clock);
        assertThat(SecretVault.kekVersion(vault.seal(AAD, "x"))).isEqualTo(1);

        stored[0] = 2; // a rotation on another replica; no refresh job has run here
        assertThat(SecretVault.kekVersion(vault.seal(AAD, "x"))).isEqualTo(1); // still within the refresh interval

        clock.now = clock.now.plus(SecretVault.REFRESH_INTERVAL).plusSeconds(1);
        byte[] blob = vault.seal(AAD, "y");
        assertThat(SecretVault.kekVersion(blob)).isEqualTo(2);
        assertThat(vault.open(AAD, blob)).isEqualTo("y");
    }

    @Test
    void sealingIsRefusedWhenTheCurrentVersionCannotBeConfirmed() {
        TestClock clock = new TestClock();
        int[] stored = {1};
        SecretVault vault = vault(new FakeProvider(1), storedVersion(stored), clock);

        stored[0] = 2; // another replica rotated; this one's provider does not hold version 2 yet
        assertThatThrownBy(vault::refreshCurrentVersion).hasMessageContaining("version 2");

        clock.now = clock.now.plus(SecretVault.REFRESH_INTERVAL).plusSeconds(1);
        assertThatThrownBy(() -> vault.seal(AAD, "x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("could not be confirmed");
        // opening keeps working
        assertThat(vault.open(AAD, sealUnder(new FakeProvider(1), 1))).isEqualTo("x");
    }

    private static SecretKeyState storedVersion(int[] stored) {
        return new SecretKeyState(null) {
            @Override
            public int currentOrInit(int initial) {
                return stored[0];
            }
        };
    }

    @Test
    void rewrapMovesTheKekAndKeepsTheCiphertext() {
        SecretVault vault = vault(new FakeProvider(1, 2));
        byte[] blob = vault.seal(AAD, "keep me");

        byte[] rewrapped = vault.rewrap(blob, 2);

        assertThat(SecretVault.kekVersion(rewrapped)).isEqualTo(2);
        assertThat(vault.open(AAD, rewrapped)).isEqualTo("keep me");
        int nonceAt = 1 + 4 + 12 + 48;
        assertThat(Arrays.copyOfRange(rewrapped, nonceAt, rewrapped.length))
                .isEqualTo(Arrays.copyOfRange(blob, nonceAt, blob.length));
        assertThat(vault.open(AAD, blob)).isEqualTo("keep me");
    }

    @Test
    void aWrappedKeyCannotBeRelabelledToAnotherVersion() {
        FakeProvider provider = new FakeProvider(1, 2);
        SecretVault vault = vault(provider, stored(1), new TestClock());
        byte[] relabelled = vault.seal(AAD, "x");
        relabelled[4] = 2;

        assertThatThrownBy(() -> vault.open(AAD, relabelled)).isInstanceOf(SecretVault.SecretDecryptException.class);
    }

    @Test
    void newSecretsUseTheStoredVersionNotTheHighest() {
        FakeProvider provider = new FakeProvider(1, 2);
        SecretVault vault = vault(provider, stored(1), new TestClock());

        assertThat(SecretVault.kekVersion(vault.seal(AAD, "x"))).isEqualTo(1);
        assertThat(vault.currentKekVersion()).isEqualTo(1);
    }

    @Test
    void startupFailsWhenTheKeyringLacksTheStoredVersion() {
        SecretVault vault = new SecretVault(new FakeProvider(1), stored(3), new TestClock());

        assertThatThrownBy(vault::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fake")
                .hasMessageContaining("version 3");
    }

    private static byte[] sealUnder(FakeProvider provider, int version) {
        return vault(provider, stored(version), new TestClock()).seal(AAD, "x");
    }

    @Test
    void aKeyringNeverPrintsKeyMaterial() {
        Keyring keyring = new Keyring(new TreeMap<>(Map.of(1, key(7), 2, key(8))));

        assertThat(keyring).hasToString("Keyring[1, 2]").hasSameHashCodeAs(new Keyring(new TreeMap<>(keyring.keys())));
    }

    @Test
    void keyringParseNamesTheProviderAndNeverTheKey() {
        String twentyBytes = Base64.getEncoder().encodeToString(new byte[20]);

        assertThatThrownBy(() -> Keyring.parse("env", "key version 1", twentyBytes))
                .hasMessageContaining("'env'")
                .hasMessageContaining("32-byte key required")
                .hasMessageNotContaining(twentyBytes);
        assertThatThrownBy(() -> Keyring.parse("env", "key version 1", "not valid base64 !!!"))
                .hasMessageContaining("base64");
    }
}
