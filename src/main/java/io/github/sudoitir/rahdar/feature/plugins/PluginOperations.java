package io.github.sudoitir.rahdar.feature.plugins;

import io.github.sudoitir.rahdar.kernel.gate.ApprovalProviderRegistry;
import io.github.sudoitir.rahdar.kernel.gate.DisplayRow;
import io.github.sudoitir.rahdar.kernel.gate.Effect;
import io.github.sudoitir.rahdar.kernel.gate.GatedOperation;
import io.github.sudoitir.rahdar.kernel.gate.Trait;
import io.github.sudoitir.rahdar.kernel.plugin.internal.host.PluginHost;
import io.github.sudoitir.rahdar.kernel.plugin.internal.host.PluginSummary;
import io.github.sudoitir.rahdar.kernel.security.AccessOperation;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The gated operations of {@link PluginAdministration}, which a replay reaches through a provider: the plugin runtime
 * collects every {@code @PluginApi} bean while it is being built, and the administration depends on that runtime. All are access control: they decide whose code runs in
 * Rahdar. They also carry {@code GATE_INTEGRITY} when they could remove or replace the approval provider, and
 * always for installers, trusted keys and the trust policy, which decide what a replacement provider may be.
 */
@Configuration(proxyBeanMethods = false)
class PluginOperations {

    private static final String LABEL_PLUGIN = "Plugin";
    private static final String LABEL_STATUS = "Status";
    private static final String UNIT_PLUGIN = "plugin";

    record ActivateUpload(String sha256, boolean acknowledge) {}

    record EnablePlugin(String id, boolean acknowledge) {}

    record RollbackPlugin(String id, boolean acknowledge) {}

    record DisablePlugin(String id, boolean cascade) {}

    record UninstallPlugin(String id, boolean cascade) {}

    record PurgePlugin(String id) {}

    record PutLicense(String id, String contentBase64, String sha256, int size) {}

    record DeleteLicense(String id) {}

    record GrantInstaller(String username) {}

    record RevokeInstaller(UUID userId) {}

    record AddTrustKey(String name, String uploadSha256, String pem) {}

    record RemoveTrustKey(String fingerprint) {}

    record SetTrustPolicy(boolean allowUnverified) {}

    /** Asked for when needed: the plugin runtime collects the gated operations while it is being built. */
    private final ObjectProvider<PluginHost> hosts;

    private final ApprovalProviderRegistry providers;

    PluginOperations(ObjectProvider<PluginHost> hosts, ApprovalProviderRegistry providers) {
        this.hosts = hosts;
        this.providers = providers;
    }

    @Bean
    GatedOperation<ActivateUpload> pluginActivateUploadOperation(ObjectProvider<PluginAdministration> service) {
        return new AccessOperation<>("plugin.activate-upload", ActivateUpload.class) {
            @Override
            public Set<Trait> traits(ActivateUpload p) {
                return access(providers.uploadDeclaresProvider(p.sha256())
                        || host().uploadPluginId(p.sha256())
                                .filter(providers::isProviderPlugin)
                                .isPresent());
            }

            @Override
            public String summary(ActivateUpload p) {
                return "Install or update plugin " + pluginOf(p) + " from an upload";
            }

            @Override
            public List<DisplayRow> display(ActivateUpload p) {
                return List.of(
                        DisplayRow.of(LABEL_PLUGIN, pluginOf(p)),
                        DisplayRow.of("Upload (sha256)", p.sha256()),
                        DisplayRow.of("Current version", versionOf(pluginOf(p))));
            }

            @Override
            public Effect estimate(ActivateUpload p) {
                return new Effect(1, UNIT_PLUGIN, etag(pluginOf(p), shaOf(pluginOf(p)), p.sha256()), null);
            }

            @Override
            public void replay(ActivateUpload p) {
                service.getObject().activate(p.sha256(), p.acknowledge());
            }
        };
    }

    @Bean
    GatedOperation<EnablePlugin> pluginEnableOperation(ObjectProvider<PluginAdministration> service) {
        return new AccessOperation<>("plugin.enable", EnablePlugin.class) {
            @Override
            public Set<Trait> traits(EnablePlugin p) {
                return access(providers.isProviderPlugin(p.id()));
            }

            @Override
            public String summary(EnablePlugin p) {
                return "Enable plugin " + p.id();
            }

            @Override
            public List<DisplayRow> display(EnablePlugin p) {
                return List.of(
                        DisplayRow.of(LABEL_PLUGIN, p.id()), new DisplayRow(LABEL_STATUS, statusOf(p.id()), "enabled"));
            }

            @Override
            public Effect estimate(EnablePlugin p) {
                return new Effect(1, UNIT_PLUGIN, etag(p.id(), shaOf(p.id()), statusOf(p.id())), null);
            }

            @Override
            public void replay(EnablePlugin p) {
                service.getObject().enable(p.id(), p.acknowledge());
            }
        };
    }

    @Bean
    GatedOperation<RollbackPlugin> pluginRollbackOperation(ObjectProvider<PluginAdministration> service) {
        return new AccessOperation<>("plugin.rollback", RollbackPlugin.class) {
            @Override
            public Set<Trait> traits(RollbackPlugin p) {
                return access(providers.isProviderPlugin(p.id()));
            }

            @Override
            public String summary(RollbackPlugin p) {
                return "Roll plugin " + p.id() + " back to its previous version";
            }

            @Override
            public List<DisplayRow> display(RollbackPlugin p) {
                return List.of(
                        DisplayRow.of(LABEL_PLUGIN, p.id()), DisplayRow.of("Current version", versionOf(p.id())));
            }

            @Override
            public Effect estimate(RollbackPlugin p) {
                return new Effect(1, UNIT_PLUGIN, etag(p.id(), shaOf(p.id())), null);
            }

            @Override
            public void replay(RollbackPlugin p) {
                service.getObject().rollback(p.id(), p.acknowledge());
            }
        };
    }

    @Bean
    GatedOperation<DisablePlugin> pluginDisableOperation(ObjectProvider<PluginAdministration> service) {
        return new AccessOperation<>("plugin.disable", DisablePlugin.class) {
            @Override
            public Set<Trait> traits(DisablePlugin p) {
                return access(removesProvider(p.id(), p.cascade()));
            }

            @Override
            public String summary(DisablePlugin p) {
                return "Disable plugin " + p.id();
            }

            @Override
            public List<DisplayRow> display(DisablePlugin p) {
                return List.of(
                        DisplayRow.of(LABEL_PLUGIN, p.id()),
                        new DisplayRow(LABEL_STATUS, statusOf(p.id()), "disabled"),
                        DisplayRow.of("Also its dependents", String.valueOf(p.cascade())));
            }

            @Override
            public Effect estimate(DisablePlugin p) {
                return new Effect(1, UNIT_PLUGIN, etag(p.id(), shaOf(p.id()), statusOf(p.id())), null);
            }

            @Override
            public void replay(DisablePlugin p) {
                service.getObject().disable(p.id(), p.cascade());
            }
        };
    }

    @Bean
    GatedOperation<UninstallPlugin> pluginUninstallOperation(ObjectProvider<PluginAdministration> service) {
        return new AccessOperation<>("plugin.uninstall", UninstallPlugin.class) {
            @Override
            public Set<Trait> traits(UninstallPlugin p) {
                return access(removesProvider(p.id(), p.cascade()));
            }

            @Override
            public String summary(UninstallPlugin p) {
                return "Uninstall plugin " + p.id();
            }

            @Override
            public List<DisplayRow> display(UninstallPlugin p) {
                return List.of(
                        DisplayRow.of(LABEL_PLUGIN, p.id()),
                        DisplayRow.of("Also its dependents", String.valueOf(p.cascade())));
            }

            @Override
            public Effect estimate(UninstallPlugin p) {
                return new Effect(1, UNIT_PLUGIN, etag(p.id(), shaOf(p.id()), statusOf(p.id())), null);
            }

            @Override
            public void replay(UninstallPlugin p) {
                service.getObject().uninstall(p.id(), p.cascade());
            }
        };
    }

    @Bean
    GatedOperation<PurgePlugin> pluginPurgeOperation(ObjectProvider<PluginAdministration> service) {
        return new AccessOperation<>("plugin.purge", PurgePlugin.class) {
            @Override
            public Set<Trait> traits(PurgePlugin p) {
                Set<Trait> traits = EnumSet.of(Trait.ACCESS_CONTROL, Trait.DESTRUCTIVE);
                if (providers.isProviderPlugin(p.id())) {
                    traits.add(Trait.GATE_INTEGRITY);
                }
                return traits;
            }

            @Override
            public String summary(PurgePlugin p) {
                return "Purge the data of plugin " + p.id();
            }

            @Override
            public List<DisplayRow> display(PurgePlugin p) {
                return List.of(DisplayRow.of(LABEL_PLUGIN, p.id()), DisplayRow.of(LABEL_STATUS, statusOf(p.id())));
            }

            @Override
            public Effect estimate(PurgePlugin p) {
                return new Effect(
                        1, UNIT_PLUGIN, etag(p.id(), shaOf(p.id()), statusOf(p.id())), "Drops the plugin's schema.");
            }

            @Override
            public void replay(PurgePlugin p) {
                service.getObject().purge(p.id(), false);
            }
        };
    }

    @Bean
    GatedOperation<PutLicense> pluginLicensePutOperation(ObjectProvider<PluginAdministration> service) {
        return new AccessOperation<>("plugin.license.put", PutLicense.class) {
            @Override
            public Set<String> redactedPaths() {
                return Set.of("/contentBase64");
            }

            @Override
            public String summary(PutLicense p) {
                return "Store a license file for plugin " + p.id();
            }

            @Override
            public List<DisplayRow> display(PutLicense p) {
                return List.of(
                        DisplayRow.of(LABEL_PLUGIN, p.id()),
                        DisplayRow.of("License file (sha256)", p.sha256()),
                        DisplayRow.of("Size", p.size() + " bytes"));
            }

            @Override
            public Effect estimate(PutLicense p) {
                return new Effect(1, "license", etag(p.id(), p.sha256()), null);
            }

            @Override
            public void replay(PutLicense p) {
                service.getObject().uploadLicense(p.id(), Base64.getDecoder().decode(p.contentBase64()), () -> {});
            }
        };
    }

    @Bean
    GatedOperation<DeleteLicense> pluginLicenseDeleteOperation(ObjectProvider<PluginAdministration> service) {
        return new AccessOperation<>("plugin.license.delete", DeleteLicense.class) {
            @Override
            public String summary(DeleteLicense p) {
                return "Remove the license file of plugin " + p.id();
            }

            @Override
            public List<DisplayRow> display(DeleteLicense p) {
                return List.of(DisplayRow.of(LABEL_PLUGIN, p.id()));
            }

            @Override
            public Effect estimate(DeleteLicense p) {
                return new Effect(1, "license", etag(p.id()), null);
            }

            @Override
            public void replay(DeleteLicense p) {
                service.getObject().removeLicense(p.id(), () -> {});
            }
        };
    }

    @Bean
    GatedOperation<GrantInstaller> pluginInstallerAddOperation(ObjectProvider<PluginAdministration> service) {
        return new AccessOperation<>("plugin.installer.add", GrantInstaller.class) {
            @Override
            public Set<Trait> traits(GrantInstaller p) {
                return access(true);
            }

            @Override
            public String summary(GrantInstaller p) {
                return "Let " + p.username() + " install plugins";
            }

            @Override
            public List<DisplayRow> display(GrantInstaller p) {
                return List.of(DisplayRow.of("User", p.username()));
            }

            @Override
            public Effect estimate(GrantInstaller p) {
                return new Effect(1, "installer", etag(p.username()), null);
            }

            @Override
            public void replay(GrantInstaller p) {
                service.getObject().grantInstaller(p.username());
            }
        };
    }

    @Bean
    GatedOperation<RevokeInstaller> pluginInstallerRemoveOperation(ObjectProvider<PluginAdministration> service) {
        return new AccessOperation<>("plugin.installer.remove", RevokeInstaller.class) {
            @Override
            public Set<Trait> traits(RevokeInstaller p) {
                return access(true);
            }

            @Override
            public String summary(RevokeInstaller p) {
                return "Stop letting a user install plugins";
            }

            @Override
            public List<DisplayRow> display(RevokeInstaller p) {
                return List.of(DisplayRow.of("User", p.userId().toString()));
            }

            @Override
            public Effect estimate(RevokeInstaller p) {
                return new Effect(1, "installer", etag(p.userId()), null);
            }

            @Override
            public void replay(RevokeInstaller p) {
                service.getObject().revokeInstaller(p.userId());
            }
        };
    }

    @Bean
    GatedOperation<AddTrustKey> pluginTrustKeyAddOperation(ObjectProvider<PluginAdministration> service) {
        return new AccessOperation<>("plugin.trust-key.add", AddTrustKey.class) {
            @Override
            public Set<Trait> traits(AddTrustKey p) {
                return access(true);
            }

            @Override
            public String summary(AddTrustKey p) {
                return "Trust the publisher key " + p.name();
            }

            @Override
            public List<DisplayRow> display(AddTrustKey p) {
                return List.of(
                        DisplayRow.of("Key name", p.name()),
                        DisplayRow.of("Taken from", p.uploadSha256() != null ? "upload " + p.uploadSha256() : "a PEM"));
            }

            @Override
            public Effect estimate(AddTrustKey p) {
                return new Effect(1, "key", etag(p.name(), p.uploadSha256(), p.pem()), null);
            }

            @Override
            public void replay(AddTrustKey p) {
                service.getObject().addKey(p.name(), p.uploadSha256(), p.pem(), () -> {});
            }
        };
    }

    @Bean
    GatedOperation<RemoveTrustKey> pluginTrustKeyRemoveOperation(ObjectProvider<PluginAdministration> service) {
        return new AccessOperation<>("plugin.trust-key.remove", RemoveTrustKey.class) {
            @Override
            public Set<Trait> traits(RemoveTrustKey p) {
                return access(true);
            }

            @Override
            public String summary(RemoveTrustKey p) {
                return "Stop trusting the publisher key " + p.fingerprint();
            }

            @Override
            public List<DisplayRow> display(RemoveTrustKey p) {
                return List.of(DisplayRow.of("Key (fingerprint)", p.fingerprint()));
            }

            @Override
            public Effect estimate(RemoveTrustKey p) {
                return new Effect(1, "key", etag(p.fingerprint()), null);
            }

            @Override
            public void replay(RemoveTrustKey p) {
                service.getObject().removeKey(p.fingerprint(), () -> {});
            }
        };
    }

    @Bean
    GatedOperation<SetTrustPolicy> pluginTrustPolicySetOperation(ObjectProvider<PluginAdministration> service) {
        return new AccessOperation<>("plugin.trust-policy.set", SetTrustPolicy.class) {
            @Override
            public Set<Trait> traits(SetTrustPolicy p) {
                return access(true);
            }

            @Override
            public String summary(SetTrustPolicy p) {
                return (p.allowUnverified() ? "Allow" : "Refuse") + " plugins that are not signed by a trusted key";
            }

            @Override
            public List<DisplayRow> display(SetTrustPolicy p) {
                return List.of(new DisplayRow(
                        "Allow unverified plugins",
                        String.valueOf(!p.allowUnverified()),
                        String.valueOf(p.allowUnverified())));
            }

            @Override
            public Effect estimate(SetTrustPolicy p) {
                return new Effect(1, "policy", etag("trust-policy"), null);
            }

            @Override
            public void replay(SetTrustPolicy p) {
                service.getObject().setAllowUnverified(p.allowUnverified(), () -> {});
            }
        };
    }

    private PluginHost host() {
        return hosts.getObject();
    }

    /** Whether disabling or uninstalling {@code id} (and, with {@code cascade}, what depends on it) could remove the provider. */
    private boolean removesProvider(String id, boolean cascade) {
        return providers.isProviderPlugin(id)
                || (cascade && providers.armedProviderId().isPresent());
    }

    private String pluginOf(ActivateUpload p) {
        return host().uploadPluginId(p.sha256()).orElse(p.sha256());
    }

    private String versionOf(String id) {
        return host().status(id).map(PluginSummary::version).orElse(null);
    }

    private String shaOf(String id) {
        return host().status(id).map(PluginSummary::sha256).orElse("none");
    }

    private String statusOf(String id) {
        return host().status(id).map(summary -> summary.status().dbValue()).orElse("not installed");
    }
}
