package io.github.sudoitir.rahdar.platform.broker;

import java.util.List;

/**
 * What an operation on a list of message ids did. When it stopped part-way, {@code error} says why
 * and {@code notDone} holds every id that was not acted on — the ones the broker refused and every
 * id after the batch that failed — so a caller never reports "failed" for an operation that already
 * acted on some messages.
 */
public record BulkResult(long affected, List<Long> notDone, String error) {

    public BulkResult {
        notDone = List.copyOf(notDone);
    }

    public boolean partial() {
        return error != null;
    }
}
