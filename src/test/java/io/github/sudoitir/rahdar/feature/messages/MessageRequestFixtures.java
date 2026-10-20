package io.github.sudoitir.rahdar.feature.messages;

import io.github.sudoitir.rahdar.feature.messages.web.MessageRequests.MessageActionRequest;
import io.github.sudoitir.rahdar.support.Targets;
import java.util.List;

/** Action requests as the tests of Artemis queues spell them: numeric positions and a target queue by name. */
public final class MessageRequestFixtures {

    private MessageRequestFixtures() {}

    public static MessageActionRequest of(List<Long> ids, String filter, String targetQueue) {
        return new MessageActionRequest(
                ids == null ? null : ids.stream().map(String::valueOf).toList(),
                filter,
                targetQueue == null || targetQueue.isBlank()
                        ? targetQueue
                        : Targets.id("artemis.queue", targetQueue).format());
    }
}
