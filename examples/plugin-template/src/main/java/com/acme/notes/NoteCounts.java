package com.acme.notes;

import io.github.sudoitir.rahdar.platform.scrape.PluginMetricSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes {@code acme-notes:notes}, the notes left on each subscription, which Rahdar samples on every
 * scrape into its metrics, Prometheus and alert rules. The query reads an indexed column of a
 * small table; a source over anything slower would keep its counts in memory instead.
 */
@Component
public class NoteCounts implements PluginMetricSource {

    @PersistenceContext
    private EntityManager em;

    @Override
    public String metric() {
        return "acme-notes:notes";
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Double> sample(UUID clusterId) {
        Map<String, Double> counts = new HashMap<>();
        em.createQuery(
                        "SELECT n.subscription, count(n) FROM Note n WHERE n.clusterId = :cluster"
                                + " GROUP BY n.subscription",
                        Object[].class)
                .setParameter("cluster", clusterId)
                .getResultList()
                .forEach(row -> counts.put((String) row[0], ((Number) row[1]).doubleValue()));
        return counts;
    }

    // To read this metric's history back, for a trend or a report, inject Rahdar's MetricHistory (from
    // io.github.sudoitir.rahdar.feature.metrics, as an ObjectProvider, since it is absent when the
    // metrics feature is off) and name the user the work acts for. The read runs with that user's grants
    // as they stand now, and answers like an unknown cluster when they have none:
    //
    //   MetricSeriesResponse series = metricHistory.readPluginMetric(
    //           actingUserId, clusterId, "acme-notes:notes", subscription, from, to, null);
}
