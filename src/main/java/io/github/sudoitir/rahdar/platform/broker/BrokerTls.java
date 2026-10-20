package io.github.sudoitir.rahdar.platform.broker;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.Dispatch;
import io.github.sudoitir.openbroker.ErrorInfo;
import io.github.sudoitir.openbroker.ErrorReasons;
import io.github.sudoitir.openbroker.Status;
import io.github.sudoitir.openbroker.TlsMaterial;
import io.github.sudoitir.openbroker.TlsProvider;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.ssl.NoSuchSslBundleException;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.ssl.SslOptions;
import org.springframework.stereotype.Component;

/**
 * A cluster's TLS from its Spring SSL bundle (ADR-0009, ADR-0098): every cluster trusts only its own
 * authority, and the JVM default context is never touched. Material built from a bundle is reused
 * until the bundle reloads, which gives it a new id so the driver releases what it built from the old.
 */
@Component
public class BrokerTls {

    private final SslBundles bundles;
    private final Map<String, TlsMaterial> current = new ConcurrentHashMap<>();
    private final Map<String, List<Runnable>> listeners = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> generations = new ConcurrentHashMap<>();

    public BrokerTls(SslBundles bundles) {
        this.bundles = bundles;
    }

    /** The cluster's TLS: none when it names no bundle. */
    public TlsProvider forCluster(BrokerConnectionSettings settings) {
        if (!settings.hasTls()) {
            return TlsProvider.none();
        }
        String bundle = settings.tlsBundle();
        boolean verify = settings.verifyHostname();
        return new TlsProvider() {
            @Override
            public Optional<TlsMaterial> current() {
                return Optional.of(material(bundle, verify));
            }

            @Override
            public void onChange(Runnable listener) {
                listeners
                        .computeIfAbsent(bundle, b -> new CopyOnWriteArrayList<>())
                        .add(listener);
            }
        };
    }

    private TlsMaterial material(String bundle, boolean verify) {
        return current.computeIfAbsent(bundle + "#" + verify, key -> {
            SslBundle resolved;
            try {
                resolved = bundles.getBundle(bundle);
            } catch (NoSuchSslBundleException e) {
                throw new BrokerException(
                        Status.UNAVAILABLE,
                        ErrorInfo.of(ErrorReasons.TLS_HANDSHAKE_FAILED),
                        Dispatch.NOT_SENT,
                        null,
                        "TLS is configured for this cluster but SSL bundle '" + bundle
                                + "' is not defined. Add it under spring.ssl.bundle.",
                        e);
            }
            if (generations.putIfAbsent(bundle, new AtomicLong()) == null) {
                bundles.addBundleUpdateHandler(bundle, reloaded -> reloaded(bundle));
            }
            SslOptions options = resolved.getOptions();
            return new TlsMaterial(
                    bundle + "@" + generations.get(bundle).get() + (verify ? "" : "/no-verify"),
                    resolved.createSslContext(),
                    verify,
                    options.getEnabledProtocols() == null ? List.of() : List.of(options.getEnabledProtocols()),
                    options.getCiphers() == null ? List.of() : List.of(options.getCiphers()));
        });
    }

    private void reloaded(String bundle) {
        generations.get(bundle).incrementAndGet();
        current.keySet().removeIf(key -> key.startsWith(bundle + "#"));
        listeners.getOrDefault(bundle, List.of()).forEach(Runnable::run);
    }
}
