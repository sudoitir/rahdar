package io.github.sudoitir.rahdar.system.kafka.web;

import io.github.sudoitir.openbroker.kafka.schema.CompatibilityLevel;
import io.github.sudoitir.openbroker.messaging.Cursor;
import io.github.sudoitir.rahdar.kernel.gate.HeldResponse;
import io.github.sudoitir.rahdar.system.kafka.KafkaSchemaBindings;
import io.github.sudoitir.rahdar.system.kafka.KafkaSchemaBindings.BindingView;
import io.github.sudoitir.rahdar.system.kafka.KafkaSchemaRegistry;
import io.github.sudoitir.rahdar.system.kafka.KafkaSchemaReviews;
import io.github.sudoitir.rahdar.system.kafka.KafkaSchemaReviews.SchemaPreview;
import io.github.sudoitir.rahdar.system.kafka.KafkaSchemaReviews.VersionView;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaSchemaViews.CompatibilityRequest;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaSchemaViews.DeleteRequest;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaSchemaViews.RegisterRequest;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaSchemaViews.SchemaBindingPage;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaSchemaViews.SchemaBindingRequest;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaSchemaViews.SubjectPage;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaSchemaViews.SubjectView;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A cluster's schema registry, under {@code /clusters/{id}/kafka/schemas}. A preview writes nothing. Apply sends the
 * preview's etag. Writes accept {@code Idempotency-Key} like every other mutation.
 */
@RestController
@RequestMapping("/clusters/{clusterId}/kafka")
@RequiredArgsConstructor
public class KafkaSchemaController {

    private final KafkaSchemaRegistry registry;
    private final KafkaSchemaReviews reviews;
    private final KafkaSchemaBindings bindings;

    @GetMapping("/schemas")
    public SubjectPage subjects(
            @PathVariable UUID clusterId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String filter) {
        var page = registry.subjects(clusterId, cursor, filter);
        return new SubjectPage(
                page.items().stream().map(KafkaSchemaViews::row).toList(),
                page.next().map(Cursor::token).orElse(null));
    }

    @GetMapping("/schemas/bindings")
    public SchemaBindingPage bindings(@PathVariable UUID clusterId) {
        return new SchemaBindingPage(bindings.list(clusterId));
    }

    @PostMapping("/schemas/bindings:preview")
    public SchemaPreview previewBinding(@PathVariable UUID clusterId, @RequestBody SchemaBindingRequest request) {
        return bindings.preview(clusterId, request.topic(), request.side(), request.serde());
    }

    @HeldResponse
    @PutMapping("/schemas/bindings")
    public BindingView bind(@PathVariable UUID clusterId, @RequestBody SchemaBindingRequest request) {
        return bindings.apply(clusterId, request.topic(), request.side(), request.serde(), request.etag());
    }

    @GetMapping("/schemas/{subject}")
    public SubjectView subject(@PathVariable UUID clusterId, @PathVariable String subject) {
        return KafkaSchemaViews.subject(registry.subject(clusterId, subject));
    }

    @PostMapping("/schemas:register-preview")
    public SchemaPreview previewRegister(@PathVariable UUID clusterId, @RequestBody RegisterRequest request) {
        return reviews.previewRegister(
                clusterId, request.subject(), KafkaSchemaViews.type(request.type()), request.schema());
    }

    @HeldResponse
    @PostMapping("/schemas:register")
    public VersionView register(@PathVariable UUID clusterId, @RequestBody RegisterRequest request) {
        return reviews.register(
                clusterId, request.subject(), KafkaSchemaViews.type(request.type()), request.schema(), request.etag());
    }

    @PostMapping("/schemas/{subject}:compatibility-preview")
    public SchemaPreview previewCompatibility(
            @PathVariable UUID clusterId, @PathVariable String subject, @RequestBody CompatibilityRequest request) {
        return reviews.previewCompatibility(clusterId, subject, KafkaSchemaViews.level(request.level()));
    }

    @HeldResponse
    @PostMapping("/schemas/{subject}:compatibility")
    public CompatibilityLevel setCompatibility(
            @PathVariable UUID clusterId, @PathVariable String subject, @RequestBody CompatibilityRequest request) {
        return reviews.setCompatibility(clusterId, subject, KafkaSchemaViews.level(request.level()), request.etag());
    }

    @PostMapping("/schemas:default-compatibility-preview")
    public SchemaPreview previewDefault(@PathVariable UUID clusterId, @RequestBody CompatibilityRequest request) {
        return reviews.previewDefault(clusterId, KafkaSchemaViews.level(request.level()));
    }

    @HeldResponse
    @PostMapping("/schemas:default-compatibility")
    public CompatibilityLevel setDefault(@PathVariable UUID clusterId, @RequestBody CompatibilityRequest request) {
        return reviews.setDefault(clusterId, KafkaSchemaViews.level(request.level()), request.etag());
    }

    @PostMapping("/schemas/{subject}:delete-preview")
    public SchemaPreview previewDelete(
            @PathVariable UUID clusterId, @PathVariable String subject, @RequestBody DeleteRequest request) {
        return reviews.previewDelete(clusterId, subject, Boolean.TRUE.equals(request.permanent()));
    }

    @HeldResponse
    @DeleteMapping("/schemas/{subject}")
    public void delete(
            @PathVariable UUID clusterId,
            @PathVariable String subject,
            @RequestParam(required = false) Boolean permanent,
            @RequestHeader(value = "If-Match", required = false) String etag) {
        reviews.delete(clusterId, subject, Boolean.TRUE.equals(permanent), KafkaSchemaViews.etag(etag));
    }
}
