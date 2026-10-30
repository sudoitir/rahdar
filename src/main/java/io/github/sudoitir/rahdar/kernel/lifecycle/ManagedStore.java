package io.github.sudoitir.rahdar.kernel.lifecycle;

import io.github.sudoitir.rahdar.kernel.plugin.PluginApi;
import java.time.Instant;

/**
 * A store the data lifecycle purges, previews and reports (ADR-0134). The engine owns the policy,
 * the schedule, the once-per-installation lock and the audit; the store only knows its own tables.
 */
@PluginApi
public interface ManagedStore {

    StoreDef def();

    StoreUsage usage();

    /** What {@link #purgeBatch} would remove, in total, for {@code cutoff}. Deletes nothing. */
    PurgeEstimate preview(Instant cutoff);

    /**
     * Removes at most one bounded batch of what is older than {@code cutoff}, in its own short
     * transaction, so a concurrent writer waits for one batch at most. Called until it returns 0.
     *
     * @param limit the most rows one batch should touch; a partition store may drop one whole
     *     expired partition instead
     * @return how many rows it removed
     */
    long purgeBatch(Instant cutoff, int limit);

    /** The quota the store starts with, in its quota unit; 0 means none until an operator sets one. */
    default int defaultQuota() {
        return 0;
    }

    /**
     * Removes at most one bounded batch of the oldest data, whatever its age, to bring a store that is over its
     * quota under it (rows only). A store that cannot trim by count leaves it to retention.
     *
     * @param excessRows how many rows the store holds beyond its quota
     * @param limit the most rows one batch should touch
     * @return how many rows it removed; 0 when it removed nothing
     */
    default long trimOldest(long excessRows, int limit) {
        return 0;
    }
}
