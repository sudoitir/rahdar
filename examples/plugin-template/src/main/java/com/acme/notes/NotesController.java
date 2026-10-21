package com.acme.notes;

import io.github.sudoitir.rahdar.kernel.plugin.ResourceType;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.PermissionResolver;
import io.github.sudoitir.rahdar.kernel.security.ResourceFilter;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The plugin's API. Rahdar forwards {@code /api/v1/clusters/{clusterId}/p/acme-notes/**} (and
 * {@code /api/v1/p/acme-notes/**} for anything outside a cluster) here, after its own sign-in
 * checks.
 *
 * <p>The permissions are {@code resource} permissions: they act on one subscription, so a platform role grants them
 * everywhere it reaches, and a team role grants them on the subscriptions the team owns. {@link ClusterAccessGuard}
 * checks one subscription and answers as Rahdar does (a subscription the caller may not read is not found; one they
 * may read and may not change is refused, naming the permission), and {@link PermissionResolver#filter} decides which
 * rows of a list the caller may see.
 */
@RestController
@RequestMapping("/api/v1/clusters/{clusterId}/p/acme-notes")
public class NotesController {

    private static final String READ = "acme-notes:read";
    private static final String WRITE = "acme-notes:write";

    private final NotesService notes;
    private final ClusterAccessGuard guard;
    private final PermissionResolver perm;

    public NotesController(NotesService notes, ClusterAccessGuard guard, PermissionResolver perm) {
        this.notes = notes;
        this.guard = guard;
        this.perm = perm;
    }

    public record NoteView(UUID id, String subscription, String author, String body, Instant createdAt) {
        static NoteView of(Note n) {
            return new NoteView(n.getId(), n.getSubscription(), n.getAuthor(), n.getBody(), n.getCreatedAt());
        }
    }

    public record NewNote(String body) {}

    /** The recent notes, only those on subscriptions the caller may read notes on. */
    @GetMapping("/notes")
    public List<NoteView> recent(@PathVariable UUID clusterId) {
        if (!perm.canAnywhere(clusterId, READ)) {
            throw new AccessDeniedException("You hold " + READ + " on no subscription of this cluster.");
        }
        ResourceFilter subscriptions = perm.filter(clusterId, ResourceType.SUBSCRIPTION);
        return notes.recent(clusterId).stream()
                .filter(n -> subscriptions.allowedActions(n.getSubscription()).contains(READ))
                .map(NoteView::of)
                .toList();
    }

    @GetMapping("/subscriptions/{subscription}/notes")
    public List<NoteView> list(@PathVariable UUID clusterId, @PathVariable String subscription) {
        guard.requireResource(clusterId, ResourceRef.subscription(subscription), READ);
        return notes.list(clusterId, subscription).stream().map(NoteView::of).toList();
    }

    @PostMapping("/subscriptions/{subscription}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    public NoteView add(@PathVariable UUID clusterId, @PathVariable String subscription, @RequestBody NewNote note) {
        guard.requireResource(clusterId, ResourceRef.subscription(subscription), WRITE);
        return NoteView.of(notes.add(clusterId, subscription, note.body() == null ? "" : note.body()));
    }

    /** A rejected note answers 400 with the reason, as a problem detail like Rahdar's own. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail invalid(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @DeleteMapping("/notes/{noteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID clusterId, @PathVariable UUID noteId) {
        // A note is on a subscription, and the right to delete it is the right on that subscription.
        notes.subscriptionOf(clusterId, noteId)
                .ifPresent(subscription ->
                        guard.requireResource(clusterId, ResourceRef.subscription(subscription), WRITE));
        notes.delete(clusterId, noteId);
    }
}
