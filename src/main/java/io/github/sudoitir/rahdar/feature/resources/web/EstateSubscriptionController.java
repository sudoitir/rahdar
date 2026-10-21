package io.github.sudoitir.rahdar.feature.resources.web;

import io.github.sudoitir.rahdar.feature.resources.EstateSubscriptions;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.EstateSubscriptionView;
import io.github.sudoitir.rahdar.kernel.core.PagedView;
import io.github.sudoitir.rahdar.kernel.core.ResourceQuery;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The subscriptions of every cluster the caller may see, whatever system each runs. */
@RestController
@RequestMapping("/subscriptions")
@RequiredArgsConstructor
public class EstateSubscriptionController {

    private final EstateSubscriptions estate;

    @GetMapping
    public PagedView<EstateSubscriptionView> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID cluster,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return estate.list(ResourceQuery.of(q, page, size, sort), cluster);
    }
}
