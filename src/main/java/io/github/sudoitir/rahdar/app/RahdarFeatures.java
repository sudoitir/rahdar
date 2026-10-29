package io.github.sudoitir.rahdar.app;

import io.github.sudoitir.rahdar.feature.alerting.AlertingFeature;
import io.github.sudoitir.rahdar.feature.alerting.AlertingModule;
import io.github.sudoitir.rahdar.feature.apitokens.ApiTokensFeature;
import io.github.sudoitir.rahdar.feature.apitokens.ApiTokensModule;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigFeature;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigModule;
import io.github.sudoitir.rahdar.feature.bulk.BulkFeature;
import io.github.sudoitir.rahdar.feature.bulk.BulkModule;
import io.github.sudoitir.rahdar.feature.diagnostics.DiagnosticsFeature;
import io.github.sudoitir.rahdar.feature.diagnostics.DiagnosticsModule;
import io.github.sudoitir.rahdar.feature.events.EventsFeature;
import io.github.sudoitir.rahdar.feature.events.EventsModule;
import io.github.sudoitir.rahdar.feature.flow.FlowFeature;
import io.github.sudoitir.rahdar.feature.flow.FlowModule;
import io.github.sudoitir.rahdar.feature.identitylocal.IdentityLocalFeature;
import io.github.sudoitir.rahdar.feature.identitylocal.IdentityLocalModule;
import io.github.sudoitir.rahdar.feature.identityoidc.IdentityOidcFeature;
import io.github.sudoitir.rahdar.feature.identityoidc.IdentityOidcModule;
import io.github.sudoitir.rahdar.feature.messages.MessagesFeature;
import io.github.sudoitir.rahdar.feature.messages.MessagesModule;
import io.github.sudoitir.rahdar.feature.metrics.MetricsFeature;
import io.github.sudoitir.rahdar.feature.metrics.MetricsModule;
import io.github.sudoitir.rahdar.feature.plugins.PluginsFeature;
import io.github.sudoitir.rahdar.feature.plugins.PluginsModule;
import io.github.sudoitir.rahdar.feature.resources.ResourcesFeature;
import io.github.sudoitir.rahdar.feature.resources.ResourcesModule;
import io.github.sudoitir.rahdar.feature.routing.RoutingFeature;
import io.github.sudoitir.rahdar.feature.routing.RoutingModule;
import io.github.sudoitir.rahdar.feature.rr.RrFeature;
import io.github.sudoitir.rahdar.feature.rr.RrModule;
import io.github.sudoitir.rahdar.feature.setupreview.SetupReviewFeature;
import io.github.sudoitir.rahdar.feature.setupreview.SetupReviewModule;
import io.github.sudoitir.rahdar.feature.sql.SqlFeature;
import io.github.sudoitir.rahdar.feature.sql.SqlModule;
import io.github.sudoitir.rahdar.feature.transfer.TransferFeature;
import io.github.sudoitir.rahdar.feature.transfer.TransferModule;
import io.github.sudoitir.rahdar.feature.triage.TriageFeature;
import io.github.sudoitir.rahdar.feature.triage.TriageModule;
import io.github.sudoitir.rahdar.kernel.approval.ApprovalModule;
import io.github.sudoitir.rahdar.kernel.audit.AuditModule;
import io.github.sudoitir.rahdar.kernel.auditfeed.AuditFeedModule;
import io.github.sudoitir.rahdar.kernel.inbox.InboxModule;
import io.github.sudoitir.rahdar.kernel.lifecycle.LifecycleModule;
import io.github.sudoitir.rahdar.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.rahdar.kernel.plugin.InstalledFeatures;
import io.github.sudoitir.rahdar.kernel.security.SecurityModule;
import io.github.sudoitir.rahdar.kernel.settings.SettingsModule;
import io.github.sudoitir.rahdar.kernel.stream.StreamModule;
import io.github.sudoitir.rahdar.platform.broker.BrokerModule;
import io.github.sudoitir.rahdar.platform.clusters.ClustersModule;
import io.github.sudoitir.rahdar.platform.governance.GovernanceModule;
import io.github.sudoitir.rahdar.platform.mcp.McpModule;
import io.github.sudoitir.rahdar.platform.scrape.ScrapeModule;
import io.github.sudoitir.rahdar.system.artemis.ArtemisModule;
import io.github.sudoitir.rahdar.system.kafka.KafkaModule;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The composition root (ADR-0069): the one list of modules built into Rahdar.
 * Adding a module is one descriptor here and, for an optional feature, one
 * {@code @Import} of its {@code <Id>Feature} configuration.
 */
@Configuration(proxyBeanMethods = false)
@Import({
    ResourcesFeature.class,
    MessagesFeature.class,
    RoutingFeature.class,
    MetricsFeature.class,
    AlertingFeature.class,
    EventsFeature.class,
    RrFeature.class,
    SqlFeature.class,
    BrokerConfigFeature.class,
    FlowFeature.class,
    TriageFeature.class,
    BulkFeature.class,
    TransferFeature.class,
    SetupReviewFeature.class,
    DiagnosticsFeature.class,
    ApiTokensFeature.class,
    PluginsFeature.class,
    IdentityLocalFeature.class,
    IdentityOidcFeature.class
})
public class RahdarFeatures {

    /** Every installed module's descriptor, enabled or not. */
    public static List<FeatureDescriptor> descriptors() {
        return List.of(
                SecurityModule.DESCRIPTOR,
                AuditModule.DESCRIPTOR,
                SettingsModule.DESCRIPTOR,
                LifecycleModule.DESCRIPTOR,
                AuditFeedModule.DESCRIPTOR,
                InboxModule.DESCRIPTOR,
                ApprovalModule.DESCRIPTOR,
                StreamModule.DESCRIPTOR,
                BrokerModule.DESCRIPTOR,
                ClustersModule.DESCRIPTOR,
                GovernanceModule.DESCRIPTOR,
                ScrapeModule.DESCRIPTOR,
                ArtemisModule.DESCRIPTOR,
                KafkaModule.DESCRIPTOR,
                McpModule.DESCRIPTOR,
                ResourcesModule.DESCRIPTOR,
                MessagesModule.DESCRIPTOR,
                RoutingModule.DESCRIPTOR,
                MetricsModule.DESCRIPTOR,
                AlertingModule.DESCRIPTOR,
                EventsModule.DESCRIPTOR,
                RrModule.DESCRIPTOR,
                SqlModule.DESCRIPTOR,
                BrokerConfigModule.DESCRIPTOR,
                FlowModule.DESCRIPTOR,
                TriageModule.DESCRIPTOR,
                BulkModule.DESCRIPTOR,
                TransferModule.DESCRIPTOR,
                SetupReviewModule.DESCRIPTOR,
                DiagnosticsModule.DESCRIPTOR,
                ApiTokensModule.DESCRIPTOR,
                PluginsModule.DESCRIPTOR,
                IdentityLocalModule.DESCRIPTOR,
                IdentityOidcModule.DESCRIPTOR);
    }

    @Bean
    InstalledFeatures installedFeatures() {
        return new InstalledFeatures(descriptors());
    }
}
