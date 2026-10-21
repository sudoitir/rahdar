package io.github.sudoitir.rahdar.feature.rr.internal.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** {@code rr_flow} access (request-reply-tracing spec). */
public interface RrFlowRepository extends JpaRepository<RrFlowEntity, UUID> {

    /** Dedupes across sample ticks (the same head message seen repeatedly is one flow). */
    Optional<RrFlowEntity> findByClusterIdAndRequestDestinationAndRequestMessageId(
            UUID clusterId, String requestDestination, String requestMessageId);

    /**
     * The reply join (design.md D3): a reply matches an awaiting-reply flow by its
     * temp-queue destination, or by either JMS correlation convention (the
     * request's own correlation id, or the request's message id echoed as the
     * reply's correlation id) — oldest in-flight first, so a reused correlation id
     * on a shared reply queue resolves to the longest-waiting request.
     */
    @Query("""
            select f from RrFlowEntity f
            where f.clusterId = :clusterId
              and f.state = 'AWAITING_REPLY'
              and ( (f.replyKind = 'TEMPORARY' and f.replyDestination = :destination)
                 or f.correlationId = :correlationId
                 or f.requestMessageId = :correlationId )
            order by f.requestedAt asc
            """)
    List<RrFlowEntity> findOpenMatches(
            @Param("clusterId") UUID clusterId,
            @Param("destination") String destination,
            @Param("correlationId") String correlationId,
            Pageable pageable);

    /** Awaiting-reply flows past their deadline — the sweep's only query, backed by {@code ix_rr_flow_deadline}. */
    List<RrFlowEntity> findByStateAndDeadlineAtBefore(String state, Instant deadline);

    /** The only responder for a request address disappearing (design.md, RESPONDER_DROPPED). */
    List<RrFlowEntity> findByClusterIdAndRequestDestinationAndState(
            UUID clusterId, String requestDestination, String state);

    /** A temp reply queue's binding removed while a flow is still awaiting reply on it. */
    List<RrFlowEntity> findByClusterIdAndReplyDestinationAndState(
            UUID clusterId, String replyDestination, String state);

    @Query("""
            select f from RrFlowEntity f
            where f.clusterId = :clusterId
              and (:state is null or f.state = :state)
              and (:everything = true or f.requestDestination in :readable)
              and (:address is null or f.requestDestination = :address)
              and (:correlationId is null or f.correlationId = :correlationId)
              and f.requestedAt >= :from
              and f.requestedAt <= :to
            order by f.requestedAt desc
            """)
    Page<RrFlowEntity> findPage(
            @Param("clusterId") UUID clusterId,
            @Param("everything") boolean everything,
            @Param("readable") Collection<String> readable,
            @Param("state") String state,
            @Param("address") String address,
            @Param("correlationId") String correlationId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);

    long countByClusterIdAndRequestDestinationAndState(UUID clusterId, String requestDestination, String state);

    Optional<RrFlowEntity> findFirstByClusterIdAndRequestDestinationAndStateOrderByRequestedAtAsc(
            UUID clusterId, String requestDestination, String state);

    @Query(
            "select distinct f.requestDestination from RrFlowEntity f where f.clusterId = :clusterId and f.requestDestination is not null")
    List<String> findDistinctRequestDestinationByClusterId(@Param("clusterId") UUID clusterId);

    long countByClusterIdAndRequestDestinationAndStateAndRequestedAtAfter(
            UUID clusterId, String requestDestination, String state, Instant since);
}
