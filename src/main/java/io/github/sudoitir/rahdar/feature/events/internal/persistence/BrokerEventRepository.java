package io.github.sudoitir.rahdar.feature.events.internal.persistence;

import io.github.sudoitir.rahdar.feature.events.BrokerEventWriter;
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

/** {@code broker_event} read access (ADR-0028). Rows are written by {@link BrokerEventWriter}. */
public interface BrokerEventRepository extends JpaRepository<BrokerEventEntity, Long> {

    /** The rows a flush inserted, in seq order, for the SSE fan-out. */
    List<BrokerEventEntity> findBySeqInOrderBySeqAsc(Collection<Long> seqs);

    /** One event of a cluster by its seq; empty when it never existed or was reaped. */
    Optional<BrokerEventEntity> findByClusterIdAndSeq(UUID clusterId, Long seq);

    /** Bounded replay for a reconnecting SSE client (slice 3). */
    List<BrokerEventEntity> findByClusterIdAndSeqGreaterThanOrderBySeqDesc(UUID clusterId, long seq, Pageable pageable);

    /** The oldest still-retained event's time, for the history API envelope. */
    @Query("select min(e.occurredAt) from BrokerEventEntity e where e.clusterId = :clusterId")
    Instant oldestRetained(@Param("clusterId") UUID clusterId);

    /**
     * Filtered, newest-first page for the events screen. Optional string filters
     * ({@code null} drops the predicate); the caller always passes a
     * {@code from}/{@code to} window widened to sentinels when unset.
     */
    @Query("""
            select e from BrokerEventEntity e
            where e.clusterId = :clusterId
              and (:type is null or e.type = :type)
              and (:nodeId is null or e.nodeId = :nodeId)
              and (:everything = true
                   or (e.destination is not null and e.destination in :readable)
                   or (e.destination is null and :unaddressed = true))
              and (:everything = true or e.subscription is null or e.subscription in :readableNames)
              and (:address is null or e.destination = :address)
              and e.occurredAt >= :from
              and e.occurredAt <= :to
            order by e.seq desc
            """)
    Page<BrokerEventEntity> findPage(
            @Param("clusterId") UUID clusterId,
            @Param("everything") boolean everything,
            @Param("readable") Collection<String> readable,
            @Param("readableNames") Collection<String> readableNames,
            @Param("unaddressed") boolean unaddressed,
            @Param("type") String type,
            @Param("nodeId") UUID nodeId,
            @Param("address") String address,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);

    /** The names events of the cluster carry as their routing name, which for a binding is a queue. */
    @Query("select distinct e.subscription from BrokerEventEntity e"
            + " where e.clusterId = :clusterId and e.subscription is not null")
    List<String> findDistinctSubscriptionByClusterId(@Param("clusterId") UUID clusterId);

    /** The addresses events of the cluster are about, for deciding which of them a reader may see. */
    @Query(
            "select distinct e.destination from BrokerEventEntity e where e.clusterId = :clusterId and e.destination is not null")
    List<String> findDistinctDestinationByClusterId(@Param("clusterId") UUID clusterId);
}
