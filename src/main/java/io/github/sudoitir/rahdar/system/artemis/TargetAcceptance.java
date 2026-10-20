package io.github.sudoitir.rahdar.system.artemis;

import io.github.sudoitir.rahdar.platform.broker.TransferAcceptance.AcceptanceFinding;
import io.github.sudoitir.rahdar.platform.broker.TransferAcceptance.AcceptanceInput;
import io.github.sudoitir.rahdar.platform.broker.TransferAcceptance.BatchVerdict;
import io.github.sudoitir.rahdar.platform.broker.TransferAcceptance.FindingKind;
import io.github.sudoitir.rahdar.system.artemis.AcceptanceProbe.Facts;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.JsonNode;

/**
 * Whether a target queue can accept a selection (transfer design D4): a pure verdict over what the
 * target node said. A refusal is anything that would lose messages silently or that the target
 * cannot hold; a warning is a risk the operator acknowledges; an unknown is a check that could not
 * be made, and never counts as passing (ADR-0049 D5).
 *
 * <p>The same rules run in the preview, over the whole selection, and before every batch of a run
 * ({@link #batch}), where a target near full makes the run wait instead of fail.
 */
final class TargetAcceptance {

    private TargetAcceptance() {}

    static List<AcceptanceFinding> evaluate(Facts f, AcceptanceInput in, String address, String queue) {
        List<AcceptanceFinding> out = refusals(in);
        if (!out.isEmpty()) {
            return out;
        }

        JsonNode settings = f.addressSettings();
        queue(in, f, settings, address, queue, out);
        policy(in, f, settings, address, out);
        disk(in, f, out);
        duplicateDetection(f, out);
        if (in.sameCluster()
                && Boolean.TRUE.equals(f.queueExists())
                && f.consumerCount() != null
                && f.consumerCount() == 0
                && settings != null
                && settings.path("redistributionDelay").asLong(-1) >= 0) {
            out.add(warn(
                    "may-redistribute",
                    "Queue %s on the target node has no consumers and redistribution is enabled for its address,"
                                    .formatted(queue)
                            + " so the cluster may move the messages to another node again.",
                    null));
        }
        return out;
    }

    /** What rules the target out before its queue is even looked at. */
    private static List<AcceptanceFinding> refusals(AcceptanceInput in) {
        List<AcceptanceFinding> out = new ArrayList<>();
        if (in.sameQueueSameNode()) {
            out.add(refuse(
                    "same-queue", "The target is the source queue on the source node, so there is nowhere to go."));
            return out;
        }
        if (in.splitBrain()) {
            out.add(refuse(
                    "target-split-brain",
                    "The target node's live/backup pair has two live members (split-brain). Messages sent now"
                            + " could land on a journal that is later discarded."));
        }
        if (in.targetBackup()) {
            out.add(refuse(
                    "target-backup",
                    "The target node is a backup. A backup accepts no messages; choose its live node."));
        } else if (!in.targetLive()) {
            out.add(refuse("target-not-live", "The target node is not live, so it cannot accept messages now."));
        }
        return out;
    }

    private static void duplicateDetection(Facts f, List<AcceptanceFinding> out) {
        if (f.idCacheSize() == null || f.persistIdCache() == null) {
            out.add(unknown(
                    "duplicate-detection",
                    "Whether the target broker detects duplicates could not be read, so an interruption at the wrong"
                            + " moment may duplicate one batch."));
        } else if (f.idCacheSize() <= 0 || !Boolean.TRUE.equals(f.persistIdCache())) {
            out.add(warn(
                    "duplicates-possible",
                    "The target broker's duplicate-id cache is %s, so if Rahdar stops between delivering a batch and"
                                    .formatted(f.idCacheSize() <= 0 ? "disabled" : "not persisted")
                            + " acknowledging it on the source, that batch may arrive twice.",
                    """
                    <id-cache-size>20000</id-cache-size>
                    <persist-id-cache>true</persist-id-cache>
                    """));
        }
    }

    private static void queue(
            AcceptanceInput in, Facts f, JsonNode settings, String address, String queue, List<AcceptanceFinding> out) {
        if (f.queueExists() == null) {
            out.add(unknown(
                    "queue-unknown", "Whether queue %s exists on the target node could not be read.".formatted(queue)));
            return;
        }
        if (Boolean.FALSE.equals(f.queueExists())) {
            queueMissing(settings, address, queue, out);
            return;
        }
        if (f.filter() == null) {
            out.add(unknown("filter-unknown", "Whether queue %s has a filter could not be read.".formatted(queue)));
        } else if (!f.filter().isBlank()) {
            out.add(refuse(
                    "target-filtered",
                    "Queue %s has the filter %s. The broker silently drops every message that does not match it."
                            .formatted(queue, f.filter())));
        }
        if (f.ringSize() != null && f.ringSize() > 0) {
            ring(in, f, queue, out);
        }
        if (Boolean.TRUE.equals(f.lastValue())) {
            out.add(warn(
                    "last-value",
                    "Queue %s is a last-value queue: messages sharing a last-value key replace each other, so fewer may"
                                    .formatted(queue)
                            + " remain than arrive.",
                    null));
        }
    }

    private static void queueMissing(JsonNode settings, String address, String queue, List<AcceptanceFinding> out) {
        JsonNode auto = settings == null ? null : settings.get("autoCreateQueues");
        if (auto == null || auto.isNull()) {
            out.add(unknown(
                    "queue-missing",
                    "Queue %s does not exist on the target node, and whether the broker would create it could not be read."
                            .formatted(queue)));
        } else if (!auto.asBoolean()) {
            out.add(refuse(
                    "queue-missing",
                    "Queue %s does not exist on the target node, and address %s does not create queues automatically."
                            .formatted(queue, address),
                    """
                    <address-setting match="%s">
                      <auto-create-queues>true</auto-create-queues>
                    </address-setting>
                    """.formatted(address)));
        }
    }

    private static void ring(AcceptanceInput in, Facts f, String queue, List<AcceptanceFinding> out) {
        long ring = f.ringSize();
        if (in.count() == null) {
            out.add(unknown(
                    "ring-unknown",
                    "Queue %s is a ring queue of %d messages, and the selection's size is unknown."
                            .formatted(queue, ring)));
        } else if (in.count() > ring) {
            out.add(refuse(
                    "ring-too-small",
                    "Queue %s is a ring queue of %d messages; the %d selected would push each other out."
                            .formatted(queue, ring, in.count())));
        } else if (in.count() + (f.messageCount() == null ? 0 : f.messageCount()) > ring) {
            out.add(warn(
                    "ring-pushes-out",
                    "Queue %s is a ring queue of %d messages; adding %d pushes out some of the messages already on it."
                            .formatted(queue, ring, in.count()),
                    null));
        }
    }

    private static void policy(
            AcceptanceInput in, Facts f, JsonNode settings, String address, List<AcceptanceFinding> out) {
        if (settings == null) {
            out.add(unknown(
                    "address-settings",
                    "The target address's settings could not be read, so whether it would drop or refuse messages is"
                            + " unknown."));
            return;
        }
        String policy = policy(settings);
        if ("DROP".equals(policy)) {
            out.add(refuse(
                    "address-full-drop",
                    "Address %s drops messages silently once it is full (address-full-policy DROP).".formatted(address),
                    policySnippet(address)));
            return;
        }
        String pageFull = text(settings, "pageFullMessagePolicy", text(settings, "pageFullPolicy", null));
        boolean pageLimit = settings.path("pageLimitBytes").asLong(-1) > 0
                || settings.path("pageLimitMessages").asLong(-1) > 0;
        if ("PAGE".equals(policy) && pageLimit && "DROP".equalsIgnoreCase(pageFull)) {
            out.add(refuse(
                    "page-full-drop",
                    "Address %s drops messages silently once its paging limit is reached (page-full-policy DROP)."
                            .formatted(address),
                    """
                    <address-setting match="%s">
                      <page-full-policy>FAIL</page-full-policy>
                    </address-setting>
                    """.formatted(address)));
        }
        if ("FAIL".equals(policy) || "BLOCK".equals(policy)) {
            boundedCapacity(in, f, settings, policy, address, out);
        }
    }

    /** An address that fails or blocks when full: does the selection fit, and is the target getting tight. */
    private static void boundedCapacity(
            AcceptanceInput in,
            Facts f,
            JsonNode settings,
            String policy,
            String address,
            List<AcceptanceFinding> out) {
        long maxBytes = settings.path("maxSizeBytes").asLong(-1);
        if (maxBytes > 0) {
            byteCapacity(in, f, maxBytes, policy, address, out);
        }
        long maxMessages = settings.path("maxSizeMessages").asLong(-1);
        if (maxMessages > 0 && in.count() != null) {
            long held = f.messageCount() == null ? 0 : f.messageCount();
            if (in.count() > maxMessages - held) {
                out.add(refuse(
                        "capacity-messages",
                        "The selection is %d messages; address %s has room for %d more."
                                .formatted(in.count(), address, Math.max(0, maxMessages - held))));
            }
        }
        if (f.addressMemoryUsagePercentage() != null && f.addressMemoryUsagePercentage() >= in.thresholdPercent()) {
            out.add(warn(
                    "memory-tight",
                    "The target broker's address memory is %d%% used; addresses that %s will do so soon."
                            .formatted(
                                    f.addressMemoryUsagePercentage(),
                                    "FAIL".equals(policy) ? "refuse messages" : "block producers"),
                    null));
        }
    }

    private static void byteCapacity(
            AcceptanceInput in, Facts f, long maxBytes, String policy, String address, List<AcceptanceFinding> out) {
        if (f.addressSize() == null || in.bytes() == null) {
            out.add(unknown(
                    "capacity-unknown",
                    "Address %s holds at most %s (%s), and %s could not be read."
                            .formatted(
                                    address,
                                    mb(maxBytes),
                                    policy,
                                    f.addressSize() == null ? "how full it is" : "the selection's size")));
            return;
        }
        long headroom = Math.max(0, maxBytes - f.addressSize());
        if (in.bytes() > headroom) {
            out.add(refuse(
                    "capacity",
                    "The selection is about %s; address %s has %s of room left before it %s."
                            .formatted(
                                    mb(in.bytes()),
                                    address,
                                    mb(headroom),
                                    "FAIL".equals(policy) ? "refuses messages" : "blocks producers")));
        } else if ((f.addressSize() + in.bytes()) * 100 >= maxBytes * in.thresholdPercent()) {
            out.add(warn(
                    "capacity-tight",
                    "After the selection (about %s), address %s would be over %d%% full; the run will wait"
                                    .formatted(mb(in.bytes()), address, in.thresholdPercent())
                            + " whenever it reaches that.",
                    null));
        }
    }

    private static void disk(AcceptanceInput in, Facts f, List<AcceptanceFinding> out) {
        if (f.diskStoreUsage() == null || f.maxDiskUsage() == null) {
            out.add(unknown("disk-unknown", "How full the target broker's disk is could not be read."));
            return;
        }
        int max = f.maxDiskUsage();
        if (max <= 0 || max >= 100) {
            return;
        }
        double used = f.diskStoreUsage() * 100;
        if (used >= max) {
            out.add(refuse(
                    "disk-full",
                    "The target broker's disk is %.0f%% used, at its max-disk-usage of %d%%: it refuses messages."
                            .formatted(used, max)));
        } else if (used >= max * in.thresholdPercent() / 100.0) {
            out.add(warn(
                    "disk-tight",
                    "The target broker's disk is %.0f%% used, near its max-disk-usage of %d%%.".formatted(used, max),
                    null));
        }
    }

    /**
     * Before a batch: a refusal that would lose messages fails the run, a target near full makes it
     * wait. What cannot be read now is not held against the run: the preview already stated it.
     */
    static BatchVerdict batch(Facts f, long batchBytes, int thresholdPercent, String address, String queue) {
        if (f.filter() != null && !f.filter().isBlank()) {
            return new BatchVerdict(
                    refuse(
                            "target-filtered",
                            "Queue %s now has the filter %s, which would drop messages silently."
                                    .formatted(queue, f.filter())),
                    null);
        }
        JsonNode settings = f.addressSettings();
        String policy = settings == null ? null : policy(settings);
        if ("DROP".equals(policy)) {
            return new BatchVerdict(
                    refuse(
                            "address-full-drop",
                            "Address %s now drops messages silently once full (address-full-policy DROP)."
                                    .formatted(address),
                            policySnippet(address)),
                    null);
        }
        if ("FAIL".equals(policy) || "BLOCK".equals(policy)) {
            BatchVerdict full = addressNearFull(f, settings, batchBytes, thresholdPercent, address);
            if (full != null) {
                return full;
            }
        }
        BatchVerdict disk = diskNearFull(f, thresholdPercent);
        return disk != null ? disk : new BatchVerdict(null, null);
    }

    /** A wait verdict when the batch would take the address to the threshold, else null. */
    private static BatchVerdict addressNearFull(
            Facts f, JsonNode settings, long batchBytes, int thresholdPercent, String address) {
        long maxBytes = settings.path("maxSizeBytes").asLong(-1);
        if (maxBytes > 0
                && f.addressSize() != null
                && (f.addressSize() + batchBytes) * 100 >= maxBytes * thresholdPercent) {
            return new BatchVerdict(
                    null,
                    "Address %s is %d%% full (%s of %s), at the %d%% threshold."
                            .formatted(
                                    address,
                                    f.addressSize() * 100 / maxBytes,
                                    mb(f.addressSize()),
                                    mb(maxBytes),
                                    thresholdPercent));
        }
        if (f.addressLimitPercent() != null && f.addressLimitPercent() >= thresholdPercent) {
            return new BatchVerdict(
                    null,
                    "Address %s is %d%% full, at the %d%% threshold."
                            .formatted(address, f.addressLimitPercent(), thresholdPercent));
        }
        return null;
    }

    private static BatchVerdict diskNearFull(Facts f, int thresholdPercent) {
        if (f.diskStoreUsage() != null && f.maxDiskUsage() != null && f.maxDiskUsage() > 0 && f.maxDiskUsage() < 100) {
            double used = f.diskStoreUsage() * 100;
            if (used >= f.maxDiskUsage() * thresholdPercent / 100.0) {
                return new BatchVerdict(
                        null,
                        "The target broker's disk is %.0f%% used, near its max-disk-usage of %d%%."
                                .formatted(used, f.maxDiskUsage()));
            }
        }
        return null;
    }

    private static String policy(JsonNode settings) {
        String p = text(settings, "addressFullMessagePolicy", "PAGE");
        return p.toUpperCase(Locale.ROOT);
    }

    private static String text(JsonNode node, String field, String absent) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() || v.asText().isBlank() ? absent : v.asText();
    }

    private static String policySnippet(String address) {
        return """
                <address-setting match="%s">
                  <address-full-policy>PAGE</address-full-policy>
                </address-setting>
                """.formatted(address);
    }

    static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0);
    }

    private static AcceptanceFinding refuse(String code, String words) {
        return new AcceptanceFinding(FindingKind.REFUSE, code, words, null);
    }

    private static AcceptanceFinding refuse(String code, String words, String snippet) {
        return new AcceptanceFinding(FindingKind.REFUSE, code, words, snippet);
    }

    private static AcceptanceFinding warn(String code, String words, String snippet) {
        return new AcceptanceFinding(FindingKind.WARN, code, words, snippet);
    }

    private static AcceptanceFinding unknown(String code, String words) {
        return new AcceptanceFinding(FindingKind.UNKNOWN, code, words, null);
    }
}
