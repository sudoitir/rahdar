package io.github.sudoitir.rahdar.feature.rr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.rahdar.feature.rr.web.RrViews.CreateExpectationRequest;
import io.github.sudoitir.rahdar.feature.rr.web.RrViews.ExpectationView;
import io.github.sudoitir.rahdar.feature.rr.web.RrViews.UpdateExpectationRequest;
import io.github.sudoitir.rahdar.kernel.audit.internal.persistence.AuditEventEntity;
import io.github.sudoitir.rahdar.kernel.audit.internal.persistence.AuditEventRepository;
import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;

/** Expectation CRUD and its audit trail (request-reply-tracing spec). */
@ExtendWith(AdminAuthenticationExtension.class)
class RequestReplyServiceTest extends PostgresIntegrationTest {

    @Autowired
    RequestReplyService service;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    AuditEventRepository audits;

    private UUID clusterId;

    @AfterEach
    void cleanUp() {
        if (clusterId != null) {
            clusters.deleteById(clusterId);
        }
    }

    private UUID cluster() {
        clusterId = clusters.save(new ClusterEntity("rr-svc-" + UUID.randomUUID(), null, null))
                .getId();
        return clusterId;
    }

    @Test
    void createIsAuditedAndEnabledByDefault() {
        cluster();
        ExpectationView created = service.create(
                clusterId, new CreateExpectationRequest("rr.request", List.of("rr.reply"), null, 30_000, 10, false));

        assertThat(created.enabled()).isTrue();
        assertThat(created.requestDestination()).isEqualTo("rr.request");
        assertThat(created.replyDestinations()).containsExactly("rr.reply");

        List<AuditEventEntity> events = audits.findByClusterIdOrderByTsDesc(clusterId);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getAction()).isEqualTo("CREATE_RR_EXPECTATION");
        assertThat(events.getFirst().getOutcome()).isEqualTo("SUCCESS");
    }

    @Test
    void aSecondExpectationForTheSameAddressConflictsRatherThanBreakingTheTransaction() {
        cluster();
        service.create(clusterId, new CreateExpectationRequest("rr.request", null, null, null, 10, false));

        var duplicate = new CreateExpectationRequest("rr.request", List.of("rr.other"), null, null, 10, false);

        assertThatThrownBy(() -> service.create(clusterId, duplicate))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("rr.request")
                .extracting(e -> ((ConflictException) e).slug())
                .isEqualTo("duplicate-rr-expectation");

        // The rejected attempt leaves no audit row: it never opened one.
        assertThat(audits.findByClusterIdOrderByTsDesc(clusterId)).hasSize(1);
        assertThat(service.list(clusterId)).hasSize(1);
    }

    @Test
    void disablingRetainsConfiguration() {
        cluster();
        ExpectationView created =
                service.create(clusterId, new CreateExpectationRequest("rr.request", null, null, null, 5, true));

        ExpectationView disabled = service.update(
                clusterId,
                created.id(),
                new UpdateExpectationRequest(
                        created.replyDestinations(),
                        created.correlationProperty(),
                        created.deadlineMs(),
                        created.samplePerMin(),
                        created.capturePayload(),
                        false));

        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.requestDestination()).isEqualTo("rr.request");
        assertThat(disabled.samplePerMin()).isEqualTo(5);
        assertThat(disabled.capturePayload()).isTrue();

        List<AuditEventEntity> events = audits.findByClusterIdOrderByTsDesc(clusterId);
        assertThat(events).hasSize(2);
        assertThat(events.getFirst().getAction()).isEqualTo("UPDATE_RR_EXPECTATION");
    }

    @Test
    void replyDestinationsRoundTripWithBlanksAndDuplicatesRemoved() {
        cluster();
        ExpectationView created = service.create(
                clusterId,
                new CreateExpectationRequest(
                        "rr.request",
                        // Blanks and repeats are what a multi-value input produces when an
                        // operator pastes a list; they must not reach the browse loop.
                        Arrays.asList("rr.reply.a", "  ", "rr.reply.*", "rr.reply.a", null, " rr.reply.b "),
                        null,
                        null,
                        10,
                        false));

        assertThat(created.replyDestinations()).containsExactly("rr.reply.a", "rr.reply.*", "rr.reply.b");

        ExpectationView reread = service.list(clusterId).getFirst();
        assertThat(reread.replyDestinations()).containsExactly("rr.reply.a", "rr.reply.*", "rr.reply.b");
    }

    @Test
    void anEmptyReplyDestinationSetIsStoredRatherThanRejected() {
        cluster();
        // An empty set is the temporary-reply-queue pattern (design.md D3), not an omission.
        ExpectationView created =
                service.create(clusterId, new CreateExpectationRequest("rr.temp", List.of(), null, null, 10, false));

        assertThat(created.replyDestinations()).isEmpty();
        assertThat(created.resolvedReplyDestinations()).isEmpty();
        assertThat(created.replyDestinationsCapped()).isFalse();
    }

    @Test
    void deleteRemovesTheExpectation() {
        cluster();
        ExpectationView created =
                service.create(clusterId, new CreateExpectationRequest("rr.request", null, null, null, 10, false));

        service.delete(clusterId, created.id());

        assertThat(service.list(clusterId)).isEmpty();
        UUID createdId = created.id();
        var update = new UpdateExpectationRequest(null, null, null, 10, false, true);
        assertThatThrownBy(() -> service.update(clusterId, createdId, update)).isInstanceOf(NotFoundException.class);
    }
}
