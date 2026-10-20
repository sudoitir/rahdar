package io.github.sudoitir.rahdar.feature.brokerconfig;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigDocument.AddressSettingDecl;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigRecommendations.Recommendation;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigRecommendations.Recommendations;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigRecommendations.RolesSourceKind;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigRecommendations.Section;
import io.github.sudoitir.rahdar.platform.broker.BrokerCapabilities;
import io.github.sudoitir.rahdar.platform.broker.BrokerCapabilities.CapabilityAssessment;
import io.github.sudoitir.rahdar.platform.broker.BrokerCapabilityProbe;
import io.github.sudoitir.rahdar.system.artemis.BrokerAccountRoles;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The capability probe's hints, turned into configuration Rahdar can apply
 * (ADR-0068). The two things that must hold: a recommendation carries the whole
 * entry it would write, and a gap Rahdar cannot close is named rather than
 * omitted.
 */
class BrokerConfigRecommendationsTest {

    private static final UUID NODE = UUID.randomUUID();

    /** The account's roles could not be read, which is what most of these tests are not about. */
    private static Recommendations from(BrokerCapabilities capabilities, ObservedNodeConfig observed) {
        return from(capabilities, observed, BrokerAccountRoles.Read.unreadable("not under test"));
    }

    private static Recommendations from(
            BrokerCapabilities capabilities, ObservedNodeConfig observed, BrokerAccountRoles.Read account) {
        return BrokerConfigRecommendations.from(capabilities, observed, account);
    }

    private static BrokerCapabilities allGaps() {
        CapabilityAssessment gap = CapabilityAssessment.unavailable("not configured");
        return new BrokerCapabilities(
                CapabilityAssessment.available("read ok"),
                CapabilityAssessment.available("write ok"),
                gap,
                CapabilityAssessment.available("Available. The broker " + BrokerCapabilityProbe.TRUNCATING + "."),
                gap);
    }

    private static ObservedNodeConfig observed(
            Map<String, Object> catchAllSettings, Map<String, Map<PermissionType, Set<String>>> security) {
        return new ObservedNodeConfig(
                NODE,
                "primary",
                true,
                Map.of(),
                Map.of(),
                Map.of("#", catchAllSettings),
                security,
                Map.of(),
                Map.of(),
                Map.of(),
                null);
    }

    @Test
    void anAddressSettingRecommendationCarriesTheKeysTheNodeAlreadyHas() {
        // addAddressSettings replaces the entry rather than merging into it (§15 M2),
        // so a recommendation that carried only its own key would reset every other
        // key on '#' the moment it was applied.
        Recommendations r =
                from(allGaps(), observed(Map.of("maxDeliveryAttempts", 7, "deadLetterAddress", "DLQ"), Map.of()));

        Recommendation slow = only(r, "slowConsumerDetection");
        assertThat(slow.appliable()).isTrue();
        assertThat(slow.match()).isEqualTo("#");
        assertThat(slow.values())
                .containsEntry("maxDeliveryAttempts", 7)
                .containsEntry("deadLetterAddress", "DLQ")
                .containsEntry("slowConsumerThreshold", 1L)
                .containsEntry("slowConsumerPolicy", "NOTIFY");
        // keys names only what this recommendation itself decided.
        assertThat(slow.keys())
                .containsExactlyInAnyOrder(
                        "slowConsumerThreshold",
                        "slowConsumerThresholdMeasurementUnit",
                        "slowConsumerCheckPeriod",
                        "slowConsumerPolicy");
    }

    @Test
    void notificationRolesArePrefilledFromWhoeverCanConsumeThere() {
        Recommendations r = from(
                allGaps(),
                observed(
                        Map.of(),
                        Map.of(
                                "activemq.notifications",
                                Map.of(PermissionType.CONSUME, Set.of("amq")),
                                "#",
                                Map.of(PermissionType.CONSUME, Set.of("everyone")))));

        Recommendation notify = appliable(r, "notifications");
        assertThat(notify.section()).isEqualTo(Section.SECURITY_SETTING);
        // The address's own roles win over the catch-all's, and all three permissions
        // are restated because Artemis matches the single most-specific block (§15 M5).
        assertThat(notify.roles())
                .containsEntry(PermissionType.CONSUME, Set.of("amq"))
                .containsEntry(PermissionType.CREATE_NON_DURABLE_QUEUE, Set.of("amq"))
                .containsEntry(PermissionType.DELETE_NON_DURABLE_QUEUE, Set.of("amq"));
    }

    @Test
    void aBrokerThatNamesNoConsumerGivesEmptyRolesAndSaysSo() {
        Recommendations r = from(allGaps(), observed(Map.of(), Map.of()));

        Recommendation notify = appliable(r, "notifications");
        assertThat(notify.roles().values())
                .allSatisfy(roles -> assertThat(roles).isEmpty());
        assertThat(notify.rationale()).contains(BrokerConfigRecommendations.FALLBACK_ROLE_NOTE);
        assertThat(notify.accountRolesSource().kind()).isEqualTo(RolesSourceKind.NONE);
        assertThat(notify.accountRolesSource().reason()).isEqualTo("not under test");
    }

    private static final Map<String, Map<PermissionType, Set<String>>> BROKER_NAMES_AMQ =
            Map.of("activemq.notifications", Map.of(PermissionType.CONSUME, Set.of("amq")));

    @Test
    void theAccountRolesTheBrokerAlreadyNamesAreTheOnesPrefilled() {
        Recommendations r = from(
                allGaps(), observed(Map.of(), BROKER_NAMES_AMQ), BrokerAccountRoles.Read.of(List.of("amq", "ops")));

        Recommendation notify = appliable(r, "notifications");
        assertThat(notify.roles().values())
                .allSatisfy(roles -> assertThat(roles).containsExactly("amq"));
        assertThat(notify.accountRoles()).containsExactly("amq", "ops");
        assertThat(notify.accountRolesSource())
                .isEqualTo(new BrokerConfigRecommendations.RolesSource(RolesSourceKind.BROKER_ACCOUNT, null));
    }

    @Test
    void allTheAccountRolesArePrefilledWhenTheBrokerNamesNoneOfThem() {
        Recommendations r =
                from(allGaps(), observed(Map.of(), BROKER_NAMES_AMQ), BrokerAccountRoles.Read.of(List.of("ops", "ro")));

        Recommendation notify = appliable(r, "notifications");
        assertThat(notify.roles().values())
                .allSatisfy(roles -> assertThat(roles).containsExactlyInAnyOrder("ops", "ro"));
        assertThat(notify.accountRolesSource().kind()).isEqualTo(RolesSourceKind.BROKER_ACCOUNT);
    }

    @Test
    void theBrokersOwnRolesAreUsedWithTheReasonWhenTheAccountCannotBeRead() {
        Recommendations r = from(
                allGaps(),
                observed(Map.of(), BROKER_NAMES_AMQ),
                BrokerAccountRoles.Read.unreadable("the broker's login module does not support listing users"));

        Recommendation notify = appliable(r, "notifications");
        assertThat(notify.roles().values())
                .allSatisfy(roles -> assertThat(roles).containsExactly("amq"));
        assertThat(notify.accountRoles()).isEmpty();
        assertThat(notify.accountRolesSource())
                .isEqualTo(new BrokerConfigRecommendations.RolesSource(
                        RolesSourceKind.SECURITY_SETTINGS, "the broker's login module does not support listing users"));
    }

    @Test
    void onlyASecuritySettingCarriesARolesSource() {
        Recommendations r = from(allGaps(), observed(Map.of(), BROKER_NAMES_AMQ));

        assertThat(only(r, "slowConsumerDetection").accountRolesSource()).isNull();
    }

    @Test
    void theGapsRahdarCannotCloseAreNamedRatherThanOmitted() {
        BrokerCapabilities refusedWrite = new BrokerCapabilities(
                CapabilityAssessment.available("read ok"),
                CapabilityAssessment.unavailable("refused"),
                CapabilityAssessment.unavailable("no"),
                CapabilityAssessment.available("ok"),
                CapabilityAssessment.available("ok"));

        List<Recommendation> manual = from(refusedWrite, observed(Map.of(), Map.of())).recommendations().stream()
                .filter(x -> !x.appliable())
                .toList();

        assertThat(manual).extracting(Recommendation::capability).contains("notifications", "managementWrite");
        assertThat(manual).allSatisfy(m -> assertThat(m.manualSnippet()).isNotBlank());
    }

    @Test
    void twoRecommendationsOnTheSameMatchCollapseIntoOneEntry() {
        Recommendations r = from(allGaps(), observed(Map.of("maxSizeBytes", 100L), Map.of()));

        BrokerConfigDocument merged =
                BrokerConfigRecommendations.merge(BrokerConfigDocument.empty(), r.recommendations());

        List<AddressSettingDecl> catchAll = merged.addressSettings().stream()
                .filter(s -> s.match().equals("#"))
                .toList();
        assertThat(catchAll).hasSize(1);
        assertThat(catchAll.getFirst().values())
                .containsEntry("maxSizeBytes", 100L)
                .containsEntry("managementMessageAttributeSizeLimit", -1L)
                .containsEntry("slowConsumerThreshold", 1L);
    }

    @Test
    void anUnreadableNodeSeedsNothingAndSaysWhich() {
        Recommendations r = from(allGaps(), ObservedNodeConfig.unreachable(NODE, "primary", "down"));

        assertThat(r.seededFrom()).isNull();
        assertThat(r.anyAppliable()).isTrue();
        // Without a seed the entry holds only its own keys; the view reports the
        // missing seed so an operator is not shown a replace they cannot check.
        assertThat(only(r, "messageIo").values()).containsOnlyKeys("managementMessageAttributeSizeLimit");
    }

    @Test
    void anAvailableCapabilityIsNotRecommended() {
        BrokerCapabilities fine = new BrokerCapabilities(
                CapabilityAssessment.available("read ok"),
                CapabilityAssessment.available("write ok"),
                CapabilityAssessment.available("subscribed"),
                CapabilityAssessment.available("whole bodies"),
                CapabilityAssessment.available("threshold 1"));

        List<Recommendation> appliable = from(fine, observed(Map.of(), Map.of())).recommendations().stream()
                .filter(Recommendation::appliable)
                .toList();

        assertThat(appliable).isEmpty();
    }

    private static Recommendation only(Recommendations r, String capability) {
        return r.recommendations().stream()
                .filter(x -> x.capability().equals(capability))
                .findFirst()
                .orElseThrow();
    }

    private static Recommendation appliable(Recommendations r, String capability) {
        return r.recommendations().stream()
                .filter(x -> x.capability().equals(capability) && x.appliable())
                .findFirst()
                .orElseThrow();
    }
}
