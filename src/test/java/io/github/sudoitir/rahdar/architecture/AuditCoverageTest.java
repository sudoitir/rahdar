package io.github.sudoitir.rahdar.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.platform.clusters.BrokerCommands;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every operator-initiated write in a feature leaves an audit row (non-negotiable #3,
 * ADR-0071): a feature class with a public, non-read-only {@code @Transactional} method
 * must use {@link AuditService} or {@link BrokerCommands}.
 *
 * <p>The exemptions are Rahdar's own bookkeeping — work no operator asked for, which an
 * audit row per tick would bury. Each carries its reason, and an exemption for a class
 * that no longer writes fails the test, so the list cannot silently outlive its cause.
 */
class AuditCoverageTest {

    private static final String BASE = "io.github.sudoitir.rahdar.";

    private static final Set<String> AUDITORS = Set.of(AuditService.class.getName(), BrokerCommands.class.getName());

    private static final Map<String, String> SYSTEM_WRITES = Map.ofEntries(
            Map.entry("feature.alerting.AlertDispatcher", "delivers notifications Rahdar already queued"),
            Map.entry("feature.alerting.AlertEvaluator", "records rule state after a scrape tier"),
            Map.entry(
                    "feature.bulk.internal.persistence.BulkRunRepository",
                    "run status bookkeeping; BulkService audits the preview and execute calls that use it"),
            Map.entry(
                    "feature.transfer.internal.persistence.TransferRunRepository",
                    "run bookkeeping; TransferService audits the preview, execute, stop, resume and return calls that use it"),
            Map.entry(
                    "feature.setupreview.SetupReviewStore",
                    "caches the latest configuration review; accepting or revoking a risk is what SetupReviewService audits"),
            Map.entry("feature.flow.FlowStore", "caches sampled client activity while a flow view is open"),
            Map.entry(
                    "feature.alerting.OutboundNoticeService",
                    "queues a plugin's notice in its caller's transaction; the operation that sends it is what is"
                            + " audited"),
            Map.entry(
                    "feature.rr.RrEventRemasker",
                    "re-masks stored payloads under a policy whose rule change was already audited"),
            Map.entry(
                    "feature.sql.MessageIndexRemasker",
                    "re-masks stored messages under a policy whose rule change was already audited"),
            Map.entry("feature.sql.CaptureLoss", "measures capture loss for the index view"),
            Map.entry(
                    "feature.plugins.internal.persistence.RegistrationRepository",
                    "registration bookkeeping; PluginMessagingService audits the register and unregister calls that use"
                            + " it, and purge removes a plugin's rows with the rest of its data"),
            Map.entry(
                    "feature.plugins.internal.persistence.OwnerWorkRepository",
                    "work bookkeeping; OwnerWorkService audits the publish, suspend, enable and withdraw calls that use"
                            + " it, and purge removes a plugin's rows with the rest of its data"));

    @Test
    void everyOperatorWriteInAFeatureIsAudited() {
        List<String> unaudited = new ArrayList<>();
        Set<String> writers = new HashSet<>();
        for (JavaClass c : new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE + "feature")) {
            if (c.getMethods().stream().noneMatch(AuditCoverageTest::isWrite)) {
                continue;
            }
            String name = c.getName().substring(BASE.length());
            writers.add(name);
            boolean audited = c.getDirectDependenciesFromSelf().stream()
                    .anyMatch(d -> AUDITORS.contains(d.getTargetClass().getName()));
            if (!audited && !SYSTEM_WRITES.containsKey(name)) {
                unaudited.add(name);
            }
        }

        assertThat(unaudited)
                .as("write through BrokerCommands or AuditService, or add a reasoned exemption")
                .isEmpty();
        assertThat(writers)
                .as("an exemption for a class that no longer writes is stale; remove it")
                .containsAll(SYSTEM_WRITES.keySet());
    }

    private static boolean isWrite(JavaMethod m) {
        return m.getModifiers().contains(JavaModifier.PUBLIC)
                && m.isAnnotatedWith(Transactional.class)
                && !m.getAnnotationOfType(Transactional.class).readOnly();
    }
}
