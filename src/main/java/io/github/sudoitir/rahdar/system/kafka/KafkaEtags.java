package io.github.sudoitir.rahdar.system.kafka;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** The state key of a preview: a hash of the parts of the cluster's state it was computed from. */
final class KafkaEtags {

    private KafkaEtags() {}

    static String of(List<String> parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                digest.update(part.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest(), 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Fails with the stale problem unless the caller's etag is the current one. */
    static void require(String given, String current) {
        if (given == null || given.isBlank()) {
            throw new IllegalArgumentException("Preview first, then send the preview's etag.");
        }
        if (!given.equals(current)) {
            throw new KafkaStaleException("The cluster changed since the preview. Preview again.");
        }
    }
}
