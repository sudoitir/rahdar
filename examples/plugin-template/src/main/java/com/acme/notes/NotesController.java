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
 * <p>The permissions are {@code resource} permissions: they act on one queue, so a platform role grants them
 * everywhere it reaches, and a team role grants them on the queues the team owns. {@link ClusterAccessGuard}
 * checks one queue and answers as Rahdar does (a queue the caller may not read is not found; one they may read
 * and may not change is refused, naming the permission), and {@link PermissionResolver#filter} decides which
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

    public record NoteView(UUID id, String queue, String author, String body, Instant createdAt) {
        static NoteView of(Note n) {
            return new NoteView(n.getId(), n.getQueue(), n.getAuthor(), n.getBody(), n.getCreatedAt());
        }
    }

    public record NewNote(String body) {}

    /** The recent notes, only those on queues the caller may read notes on. */
    @GetMapping("/notes")
    public List<NoteView> recent(@PathVariable UUID clusterId) {
        if (!perm.canAnywhere(clusterId, READ)) {
            throw new AccessDeniedException("You hold " + READ + " on no queue of this cluster.");
        }
        ResourceFilter queues = perm.filter(clusterId, ResourceType.SUBSCRIPTION);
        return notes.recent(clusterId).stream()
                .filter(n -> queues.allowedActions(n.getQueue()).contains(READ))
                .map(NoteView::of)
                .toList();
    }

    @GetMapping("/queues/{queue}/notes")
    public List<NoteView> list(@PathVariable UUID clusterId, @PathVariable String queue) {
        guard.requireResource(clusterId, ResourceRef.subscription(queue), READ);
        return notes.list(clusterId, queue).stream().map(NoteView::of).toList();
    }

    @PostMapping("/queues/{queue}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    public NoteView add(@PathVariable UUID clusterId, @PathVariable String queue, @RequestBody NewNote note) {
        guard.requireResource(clusterId, ResourceRef.subscription(queue), WRITE);
        return NoteView.of(notes.add(clusterId, queue, note.body() == null ? "" : note.body()));
    }

    /** A rejected note answers 400 with the reason, as a problem detail like Rahdar's own. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail invalid(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @DeleteMapping("/notes/{noteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID clusterId, @PathVariable UUID noteId) {
        // A note is on a queue, and the right to delete it is the right on that queue.
        notes.queueOf(clusterId, noteId)
                .ifPresent(queue -> guard.requireResource(clusterId, ResourceRef.subscription(queue), WRITE));
        notes.delete(clusterId, noteId);
    }
}
