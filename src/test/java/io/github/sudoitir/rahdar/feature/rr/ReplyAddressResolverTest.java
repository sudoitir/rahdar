package io.github.sudoitir.rahdar.feature.rr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.feature.rr.internal.persistence.RrExpectationEntity;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshots;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Glob semantics and the resolution cap (design.md D1, D4, D7). Pure unit tests —
 * the only collaborator is the snapshot repository, stubbed, because the point of
 * resolving from {@code subscription_snapshot} is that no broker is involved.
 */
class ReplyAddressResolverTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    private static RrExpectationEntity expecting(String... replyAddresses) {
        return new RrExpectationEntity(CLUSTER, "rr.request", List.of(replyAddresses), null, null, 10, false);
    }

    private static ReplyAddressResolver resolverOver(String... knownAddresses) {
        QueueSnapshots snapshots = mock(QueueSnapshots.class);
        when(snapshots.addresses(any())).thenReturn(List.of(knownAddresses));
        return new ReplyAddressResolver(snapshots);
    }

    @Test
    void aLiteralResolvesToItselfEvenWhenTheScrapeHasNotSeenIt() {
        // A literal is browsable whether or not the last scrape happened to see it;
        // requiring a snapshot would break an expectation on a freshly-registered cluster.
        ReplyAddressResolver resolver = resolverOver();

        assertThat(resolver.resolve(CLUSTER, expecting("orders.reply")).addresses())
                .containsExactly("orders.reply");
    }

    @Test
    void aGlobResolvesToEveryMatchingKnownAddress() {
        ReplyAddressResolver resolver =
                resolverOver("orders.reply.host-1", "orders.reply.host-2", "orders.request", "billing.reply.host-1");

        assertThat(resolver.resolve(CLUSTER, expecting("orders.reply.*")).addresses())
                .containsExactly("orders.reply.host-1", "orders.reply.host-2");
    }

    @Test
    void matchingIsAnchoredAtBothEnds() {
        RrExpectationEntity e = expecting("orders.reply.*");

        assertThat(new ReplyAddressResolver(mock(QueueSnapshots.class)).matches(e, "orders.reply.x"))
                .isTrue();
        // The whole address must match, not a substring of it.
        assertThat(new ReplyAddressResolver(mock(QueueSnapshots.class)).matches(e, "legacy.orders.reply.x"))
                .isFalse();
        assertThat(new ReplyAddressResolver(mock(QueueSnapshots.class)).matches(e, "orders.reply"))
                .isFalse();
    }

    @Test
    void regexMetacharactersInAPatternAreLiteral() {
        // A glob is not a regular expression: '.' is a dot, '+' is a plus (design.md D1).
        ReplyAddressResolver resolver = resolverOver("ordersXreply.a", "orders.reply.a");

        assertThat(resolver.resolve(CLUSTER, expecting("orders.reply.*")).addresses())
                .containsExactly("orders.reply.a");

        RrExpectationEntity plus = expecting("a+b");
        assertThat(resolver.matches(plus, "a+b")).isTrue();
        assertThat(resolver.matches(plus, "aab")).isFalse();
    }

    @Test
    void anEmptySetResolvesToNothingAndMatchesNothing() {
        // The temporary-reply-queue pattern (D3): no shared queue to browse at all.
        ReplyAddressResolver resolver = resolverOver("orders.reply.a");
        RrExpectationEntity e = expecting();

        assertThat(resolver.resolve(CLUSTER, e).isEmpty()).isTrue();
        assertThat(resolver.matches(e, "orders.reply.a")).isFalse();
    }

    @Test
    void anOverBroadPatternIsCappedAndSaysSo() {
        String[] many = IntStream.range(0, ReplyAddressResolver.MAX_RESOLVED + 10)
                .mapToObj(i -> "orders.reply." + i)
                .toArray(String[]::new);
        ReplyAddressResolver resolver = resolverOver(many);

        ReplyAddressResolver.Resolution resolution = resolver.resolve(CLUSTER, expecting("*"));

        assertThat(resolution.addresses()).hasSize(ReplyAddressResolver.MAX_RESOLVED);
        assertThat(resolution.capped()).isTrue();
    }

    @Test
    void aPatternThatMatchesNothingYetIsNotAnError() {
        // The reply queue simply has not been created; tracing starts when it appears.
        ReplyAddressResolver resolver = resolverOver("orders.request");

        ReplyAddressResolver.Resolution resolution = resolver.resolve(CLUSTER, expecting("orders.reply.*"));

        assertThat(resolution.isEmpty()).isTrue();
        assertThat(resolution.capped()).isFalse();
    }

    @Test
    void onlyASingleLiteralIsKnownInAdvance() {
        ReplyAddressResolver resolver = resolverOver("orders.reply.a", "orders.reply.b");

        assertThat(resolver.resolve(CLUSTER, expecting("orders.reply")).singleLiteral())
                .isTrue();
        assertThat(resolver.resolve(CLUSTER, expecting("orders.reply.a", "orders.reply.b"))
                        .singleLiteral())
                .isFalse();
    }

    @Test
    void aGlobIsNeverKnownInAdvanceEvenWhenItMatchesExactlyOneAddressToday() {
        // The address it resolves to today is a fact about this scrape, not about the
        // expectation: a second responder tomorrow makes the stamp wrong. The flow
        // takes its destination from the joining reply instead (design.md D5).
        ReplyAddressResolver resolver = resolverOver("orders.reply.only-host");

        ReplyAddressResolver.Resolution resolution = resolver.resolve(CLUSTER, expecting("orders.reply.*"));

        assertThat(resolution.addresses()).containsExactly("orders.reply.only-host");
        assertThat(resolution.singleLiteral()).isFalse();
    }
}
