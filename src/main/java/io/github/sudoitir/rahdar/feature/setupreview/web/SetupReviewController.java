package io.github.sudoitir.rahdar.feature.setupreview.web;

import io.github.sudoitir.rahdar.feature.setupreview.SetupReviewService;
import io.github.sudoitir.rahdar.feature.setupreview.web.SetupReviewViews.AcceptRiskRequest;
import io.github.sudoitir.rahdar.feature.setupreview.web.SetupReviewViews.SetupReviewView;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** A cluster's setup review (cluster-setup-review spec, ADR-0106). */
@RestController
@RequestMapping("/clusters/{clusterId}/artemis/setup-review")
@RequiredArgsConstructor
public class SetupReviewController {

    private final SetupReviewService reviews;

    @GetMapping
    public SetupReviewView setupReview(@PathVariable UUID clusterId) {
        return reviews.view(clusterId);
    }

    /** Reviews now; within the minimum spacing, returns the last review with a {@code notice}. */
    @PostMapping("/run")
    public SetupReviewView runSetupReview(@PathVariable UUID clusterId) {
        return reviews.run(clusterId);
    }

    @PostMapping("/acceptances")
    public SetupReviewView acceptSetupRisk(
            @PathVariable UUID clusterId, @Valid @RequestBody AcceptRiskRequest request) {
        return reviews.accept(clusterId, request);
    }

    @DeleteMapping("/acceptances")
    public SetupReviewView revokeSetupRisk(
            @PathVariable UUID clusterId, @RequestParam String code, @RequestParam String subject) {
        return reviews.revoke(clusterId, code, subject);
    }
}
