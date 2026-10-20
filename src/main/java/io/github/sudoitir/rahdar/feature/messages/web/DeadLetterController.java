package io.github.sudoitir.rahdar.feature.messages.web;

import io.github.sudoitir.rahdar.feature.messages.DlqService;
import io.github.sudoitir.rahdar.feature.messages.web.MessageViews.DeadLetterView;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dead-letter / expiry management (ADR-0021, D8). The addresses come from the
 * broker's own address settings — when that read fails the response says so
 * ({@code settingsAvailable = false}) rather than guessing from names.
 */
@RestController
@RequestMapping("/clusters/{clusterId}/dead-letters")
@RequiredArgsConstructor
public class DeadLetterController {

    private final DlqService dlq;

    @GetMapping
    public DeadLetterView view(@PathVariable UUID clusterId) {
        return dlq.view(clusterId);
    }
}
