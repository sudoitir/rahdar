package io.github.sudoitir.rahdar.platform.broker;

/** A queue's depth and the part of it in flight: one read of three attributes. */
public record QueueDepth(long messageCount, long deliveringCount, long scheduledCount) {
    /** What a browser can see: neither delivered-unacked nor scheduled messages. */
    public long browsable() {
        return Math.max(0, messageCount - deliveringCount - scheduledCount);
    }
}
