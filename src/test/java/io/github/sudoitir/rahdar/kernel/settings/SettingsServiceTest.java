package io.github.sudoitir.rahdar.kernel.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.sudoitir.rahdar.feature.alerting.AlertingSettings;
import io.github.sudoitir.rahdar.feature.identitylocal.IdentityLocalSettings;
import io.github.sudoitir.rahdar.kernel.approval.ApprovalSettings;
import io.github.sudoitir.rahdar.kernel.audit.internal.persistence.AuditEventEntity;
import io.github.sudoitir.rahdar.kernel.audit.internal.persistence.AuditEventRepository;
import io.github.sudoitir.rahdar.kernel.gate.GatePreview;
import io.github.sudoitir.rahdar.kernel.lifecycle.LifecycleSettings;
import io.github.sudoitir.rahdar.kernel.security.SettingsPermissions;
import io.github.sudoitir.rahdar.kernel.settings.internal.persistence.RahdarSettingEntity;
import io.github.sudoitir.rahdar.kernel.settings.internal.persistence.RahdarSettingRepository;
import io.github.sudoitir.rahdar.kernel.settings.web.SettingsViews.SettingValue;
import io.github.sudoitir.rahdar.platform.broker.BrokerSettings;
import io.github.sudoitir.rahdar.platform.broker.NodeCallLimiter;
import io.github.sudoitir.rahdar.platform.scrape.ScrapeSettings;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;

/** {@link SettingsService}: default fall-through, put-then-get, validation, and live limiter wiring. */
@ExtendWith(AdminAuthenticationExtension.class)
class SettingsServiceTest extends PostgresIntegrationTest {

    @Autowired
    SettingsService settings;

    @Autowired
    RahdarSettingRepository repo;

    @Autowired
    NodeCallLimiter limiter;

    @Autowired
    AuditEventRepository auditEvents;

    @AfterEach
    void cleanUp() {
        repo.deleteAll();
        auditEvents.deleteAll();
        settings.applyRuntime();
    }

    @Test
    void unsetKeysFallThroughToTheApplicationYmlDefaults() {
        assertThat(settings.duration(ScrapeSettings.TIER_A)).isEqualTo(Duration.ofSeconds(5));
        assertThat(settings.intValue(BrokerSettings.RATE_LIMIT)).isEqualTo(20);
        assertThat(settings.effective().get(ScrapeSettings.TIER_A).overridden()).isFalse();
    }

    @Test
    void putThenGetReturnsTheOverrideAndFlagsIt() {
        settings.put(ScrapeSettings.TIER_B, "30s");
        settings.put(BrokerSettings.RATE_LIMIT, "3");

        assertThat(settings.duration(ScrapeSettings.TIER_B)).isEqualTo(Duration.ofSeconds(30));
        assertThat(settings.intValue(BrokerSettings.RATE_LIMIT)).isEqualTo(3);
        assertThat(settings.effective().get(ScrapeSettings.TIER_B).overridden()).isTrue();
        assertThat(settings.effective().get(ScrapeSettings.TIER_B).defaultValue())
                .isEqualTo("PT15S");
    }

    @Test
    void settingTheLimiterAppliesToTheLiveHolder() {
        settings.put(BrokerSettings.RATE_LIMIT, "9");

        assertThat(limiter.permitsPerSecond()).isEqualTo(9);
    }

    @Test
    void resetClearsTheOverride() {
        settings.put(BrokerSettings.RATE_LIMIT, "9");
        settings.reset(BrokerSettings.RATE_LIMIT);

        assertThat(settings.intValue(BrokerSettings.RATE_LIMIT)).isEqualTo(20);
        assertThat(settings.effective().get(BrokerSettings.RATE_LIMIT).overridden())
                .isFalse();
    }

    @Test
    void eachSettingSaysTheRangeItAccepts() {
        var effective = settings.effective();

        assertThat(effective.get(ApprovalSettings.MAX_OPEN_PER_REQUESTER))
                .extracting(SettingValue::min, SettingValue::max)
                .containsExactly("1", "1000");
        assertThat(effective.get(BrokerSettings.RATE_LIMIT))
                .extracting(SettingValue::min, SettingValue::max)
                .containsExactly("1", null);
        assertThat(effective.get(ScrapeSettings.TIER_A).max()).isNull();
    }

    @Test
    void invalidValuesAreRejected() {
        assertThatThrownBy(() -> settings.put(ScrapeSettings.TIER_A, "0s"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings.put(BrokerSettings.RATE_LIMIT, "0"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings.put("bogus.key", "1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aDurationOrOffAcceptsZeroButNotANegativeDuration() {
        settings.put(IdentityLocalSettings.TRUSTED_DEVICE_LIFETIME, "0");

        assertThat(settings.duration(IdentityLocalSettings.TRUSTED_DEVICE_LIFETIME))
                .isEqualTo(Duration.ZERO);
        settings.put(IdentityLocalSettings.TRUSTED_DEVICE_LIFETIME, "7d");
        assertThat(settings.duration(IdentityLocalSettings.TRUSTED_DEVICE_LIFETIME))
                .isEqualTo(Duration.ofDays(7));
        assertThatThrownBy(() -> settings.put(IdentityLocalSettings.TRUSTED_DEVICE_LIFETIME, "-5m"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(settings.effective()
                        .get(IdentityLocalSettings.TRUSTED_DEVICE_LIFETIME)
                        .defaultValue())
                .isEqualTo("PT720H");
    }

    /** One key of each {@link SettingDef.Kind}, so a new kind cannot land untested. */
    @Test
    void everyKindRoundTrips() {
        settings.put(BrokerSettings.READ_TIMEOUT, "45s");
        settings.put(IdentityLocalSettings.TRUSTED_DEVICE_LIFETIME, "12h");
        settings.put(AlertingSettings.MAX_ATTEMPTS, "9");
        settings.put(LifecycleSettings.HOUSEKEEPING_CRON, "0 45 4 * * *");

        assertThat(settings.duration(BrokerSettings.READ_TIMEOUT)).isEqualTo(Duration.ofSeconds(45));
        assertThat(settings.duration(IdentityLocalSettings.TRUSTED_DEVICE_LIFETIME))
                .isEqualTo(Duration.ofHours(12));
        assertThat(settings.intValue(AlertingSettings.MAX_ATTEMPTS)).isEqualTo(9);
        assertThat(settings.value(LifecycleSettings.HOUSEKEEPING_CRON)).isEqualTo("0 45 4 * * *");
        assertThat(settings.effective().get(LifecycleSettings.HOUSEKEEPING_CRON).kind())
                .isEqualTo("CRON");
    }

    /**
     * A cron that fires more often than once a minute is refused, not clamped. These
     * schedules drive bulk deletes and DDL, so a stray seconds field turning a nightly
     * trim into a per-second one has to fail loudly at the point it is typed.
     */
    @Test
    void aCronThatFiresTooOftenIsRejected() {
        assertThatThrownBy(() -> settings.put(LifecycleSettings.HOUSEKEEPING_CRON, "* * * * * *"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings.put(LifecycleSettings.HOUSEKEEPING_CRON, "not a cron"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(settings.value(LifecycleSettings.HOUSEKEEPING_CRON)).isEqualTo("0 30 3 * * *");
    }

    /** Every key is described well enough for the settings screen to render it unaided. */
    @Test
    void everyKeyCarriesItsOwnFormMetadata() {
        assertThat(settings.effective()).allSatisfy((key, value) -> {
            assertThat(value.group()).as("group of %s", key).isNotBlank();
            assertThat(value.label()).as("label of %s", key).isNotBlank();
            assertThat(value.hint()).as("hint of %s", key).isNotBlank();
            assertThat(value.kind()).as("kind of %s", key).isNotBlank();
        });
    }

    @Test
    void changingASettingIsAudited() {
        settings.put(BrokerSettings.BULK_CAP, "50");
        settings.reset(BrokerSettings.BULK_CAP);

        // Sorted by the generated id rather than trusting findAll()'s order: an
        // unordered SELECT may return either row first, and asserting a sequence on
        // it passes or fails by luck. The id is monotonic, so this still pins that
        // the update was recorded before the reset.
        assertThat(auditEvents.findAll().stream()
                        .sorted(Comparator.comparing(AuditEventEntity::getId))
                        .toList())
                .extracting(AuditEventEntity::getAction, AuditEventEntity::getTargetName)
                .containsExactly(
                        tuple("UPDATE_SETTING", BrokerSettings.BULK_CAP),
                        tuple("RESET_SETTING", BrokerSettings.BULK_CAP));
    }

    /**
     * A rejected value is not an audit event: validation runs before the write, so
     * nothing was changed and there is no transaction to record. This pins that,
     * because the tempting alternative — audit first, then validate — records a
     * change that then rolls back with the rest of the transaction.
     */
    @Test
    void aRejectedChangeWritesNoAuditRow() {
        assertThatThrownBy(() -> settings.put(BrokerSettings.BULK_CAP, "0"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(auditEvents.findAll()).isEmpty();
    }

    @Test
    void aSettingRegisteredAtRuntimeTakesItsStoredOverride() {
        repo.save(new RahdarSettingEntity("acme.retention", "\"30d\""));
        SettingDef def = new SettingDef(
                "acme.retention", "g", "l", "h", SettingDef.Kind.DURATION, () -> "7d", null, "1d", "90d");

        settings.addSettings("acme", List.of(def), SettingsPermissions.SETTINGS_WRITE);

        assertThat(settings.value("acme.retention")).isEqualTo("30d");
        settings.removeSettings("acme");
    }

    @Test
    void aChangeSetAppliesSettingsAndResetsTogether() {
        settings.put(BrokerSettings.BULK_CAP, "3");

        settings.apply(List.of(
                SettingChange.reset(BrokerSettings.BULK_CAP),
                SettingChange.set(BrokerSettings.BULK_SUBSCRIPTION_CAP, "2")));

        assertThat(settings.effective().get(BrokerSettings.BULK_CAP).overridden())
                .isFalse();
        assertThat(settings.intValue(BrokerSettings.BULK_SUBSCRIPTION_CAP)).isEqualTo(2);
        assertThat(auditEvents.findAll())
                .extracting(AuditEventEntity::getAction, AuditEventEntity::getTargetName)
                .contains(
                        tuple("RESET_SETTING", BrokerSettings.BULK_CAP),
                        tuple("UPDATE_SETTING", BrokerSettings.BULK_SUBSCRIPTION_CAP));
    }

    @Test
    void aChangeSetWithOneInvalidValueChangesNothingAndNamesTheInvalidSetting() {
        assertThatThrownBy(() -> settings.apply(List.of(
                        SettingChange.set(BrokerSettings.BULK_CAP, "3"),
                        SettingChange.set(BrokerSettings.BULK_SUBSCRIPTION_CAP, "0"),
                        SettingChange.set(ScrapeSettings.TIER_A, "soon"))))
                .isInstanceOfSatisfying(
                        SettingsInvalidException.class,
                        e -> assertThat(e.fieldErrors())
                                .containsOnlyKeys(BrokerSettings.BULK_SUBSCRIPTION_CAP, ScrapeSettings.TIER_A));

        assertThat(settings.effective().get(BrokerSettings.BULK_CAP).overridden())
                .isFalse();
        assertThat(auditEvents.findAll()).isEmpty();
    }

    @Test
    void aSettingAppearingTwiceInAChangeSetIsRefused() {
        var changes =
                List.of(SettingChange.set(BrokerSettings.BULK_CAP, "3"), SettingChange.reset(BrokerSettings.BULK_CAP));

        assertThatThrownBy(() -> settings.apply(changes))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("twice");
    }

    @Test
    void withoutAProviderThePreviewSaysRunAndReportsInvalidFields() {
        assertThat(settings.preview(List.of(SettingChange.set(BrokerSettings.BULK_CAP, "3"))))
                .satisfies(preview -> {
                    assertThat(preview.outcome()).isEqualTo(GatePreview.Outcome.RUN);
                    assertThat(preview.fieldErrors()).isEmpty();
                });
        assertThat(settings.preview(List.of(SettingChange.set(BrokerSettings.BULK_CAP, "0"))))
                .satisfies(preview -> {
                    assertThat(preview.outcome()).isNull();
                    assertThat(preview.fieldErrors()).containsOnlyKeys(BrokerSettings.BULK_CAP);
                });
        assertThat(settings.effective().get(BrokerSettings.BULK_CAP).overridden())
                .isFalse();
    }

    @Test
    void everySettingNamesItsCategoryAndHasNoPendingChange() {
        var cap = settings.effective().get(BrokerSettings.BULK_CAP);

        assertThat(cap.category()).isNotBlank();
        assertThat(cap.categoryTitle()).isNotBlank();
        assertThat(cap.defaultValue()).isNotBlank();
        assertThat(cap.pending()).isEmpty();
    }
}
