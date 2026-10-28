package io.github.sudoitir.rahdar.system.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.kafka.admin.KafkaKinds;
import io.github.sudoitir.openbroker.model.Attributes;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.kernel.gate.OperationGate;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.system.kafka.KafkaTransactionAborts.AbortParams;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.AbortPreview;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Only an open transaction that has outlived its timeout is aborted; a live one is never fenced. */
class KafkaTransactionAbortsTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    private final KafkaTransactions transactions = mock(KafkaTransactions.class);
    private final BrokerGateway gateway = mock(BrokerGateway.class);
    private final AuditService audit = mock(AuditService.class);
    private final KafkaTransactionAborts aborts = new KafkaTransactionAborts(
            transactions, gateway, mock(OperationGate.class), audit, mock(ActorResolver.class));

    private static Resource transaction(String state, Duration age, long timeoutMs) {
        return new Resource(
                ResourceId.of("kafka.transactional-id", "tx"),
                Attributes.builder()
                        .putRaw(KafkaKinds.TRANSACTION_STATE, state)
                        .putRaw(KafkaKinds.TRANSACTION_TIMEOUT_MS, timeoutMs)
                        .putRaw(KafkaKinds.TRANSACTION_START, Instant.now().minus(age))
                        .build(),
                List.of(),
                List.of(),
                Instant.now());
    }

    @Test
    void onlyAnOpenTransactionPastItsTimeoutIsHanging() {
        Instant now = Instant.now();

        assertThat(KafkaTransactions.hanging(transaction("ongoing", Duration.ofMinutes(20), 60_000), now))
                .isTrue();
        assertThat(KafkaTransactions.hanging(transaction("ongoing", Duration.ofSeconds(5), 60_000), now))
                .isFalse();
        assertThat(KafkaTransactions.hanging(transaction("complete_commit", Duration.ofHours(2), 60_000), now))
                .isFalse();
    }

    @Test
    void aTransactionThatIsNotHangingIsRefusedBeforeTheGateAndTheAuditRow() {
        when(transactions.abortPreview(CLUSTER, "tx"))
                .thenReturn(
                        new AbortPreview("tx", "ongoing", List.of(), "its producer is still working", false, "etag"));

        assertThatThrownBy(() -> aborts.abort(CLUSTER, "tx", "etag"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("still working");
        assertThatThrownBy(() -> aborts.replay(new AbortParams(CLUSTER, "tx", "etag")))
                .isInstanceOf(ConflictException.class);

        verify(audit, never())
                .begin(any(), any(), any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }
}
