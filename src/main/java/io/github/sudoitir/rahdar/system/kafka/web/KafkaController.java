package io.github.sudoitir.rahdar.system.kafka.web;

import io.github.sudoitir.rahdar.kernel.gate.HeldResponse;
import io.github.sudoitir.rahdar.system.kafka.KafkaAclChanges;
import io.github.sudoitir.rahdar.system.kafka.KafkaAcls;
import io.github.sudoitir.rahdar.system.kafka.KafkaGroups;
import io.github.sudoitir.rahdar.system.kafka.KafkaHangingAborts;
import io.github.sudoitir.rahdar.system.kafka.KafkaHangingTransactions;
import io.github.sudoitir.rahdar.system.kafka.KafkaOffsetResets;
import io.github.sudoitir.rahdar.system.kafka.KafkaOffsets;
import io.github.sudoitir.rahdar.system.kafka.KafkaTopicConfigChanges;
import io.github.sudoitir.rahdar.system.kafka.KafkaTopicConfigs;
import io.github.sudoitir.rahdar.system.kafka.KafkaTransactionAborts;
import io.github.sudoitir.rahdar.system.kafka.KafkaTransactions;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.AbortPreview;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.AbortRequest;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.AbortResult;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.AclChangeRequest;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.AclPage;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.AclPreview;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.AclResult;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.ConfigChangeRequest;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.ConfigPreview;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.GroupDetail;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.GroupPage;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.HangingAbortPreview;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.HangingAbortRequest;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.HangingAbortResult;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.HangingPage;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.OffsetPreview;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.OffsetRequest;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.OffsetResult;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.TopicConfig;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.TransactionPage;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What only Kafka has, under {@code /clusters/{id}/kafka/} (ADR-0216): another system's cluster answers these with
 * {@code 404} before any broker call. Lists page with an opaque {@code cursor} and a {@code limit} (default 100, at
 * most 500). Every mutation is previewed first and carries the preview's {@code etag}.
 */
@RestController
@RequestMapping("/clusters/{clusterId}/kafka")
@RequiredArgsConstructor
public class KafkaController {

    private final KafkaGroups groups;
    private final KafkaOffsets offsets;
    private final KafkaOffsetResets resets;
    private final KafkaAcls acls;
    private final KafkaAclChanges aclChanges;
    private final KafkaTransactions transactions;
    private final KafkaTransactionAborts aborts;
    private final KafkaHangingTransactions hanging;
    private final KafkaHangingAborts hangingAborts;
    private final KafkaTopicConfigs configs;
    private final KafkaTopicConfigChanges configChanges;

    @GetMapping("/groups")
    public GroupPage groups(
            @PathVariable UUID clusterId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return groups.list(clusterId, type, state, q, cursor, limit);
    }

    @GetMapping("/groups/{group}")
    public GroupDetail group(@PathVariable UUID clusterId, @PathVariable String group) {
        return groups.detail(clusterId, group);
    }

    @PostMapping("/groups/{group}/offsets:preview")
    public OffsetPreview previewOffsets(
            @PathVariable UUID clusterId, @PathVariable String group, @RequestBody OffsetRequest request) {
        return offsets.preview(clusterId, group, request);
    }

    @HeldResponse
    @PostMapping("/groups/{group}/offsets:reset")
    public OffsetResult resetOffsets(
            @PathVariable UUID clusterId, @PathVariable String group, @RequestBody OffsetRequest request) {
        return resets.reset(clusterId, group, request);
    }

    @GetMapping("/topics/{topic}/config")
    public TopicConfig topicConfig(@PathVariable UUID clusterId, @PathVariable String topic) {
        return configs.read(clusterId, topic);
    }

    @PostMapping("/topics/{topic}/config:preview")
    public ConfigPreview previewConfig(
            @PathVariable UUID clusterId, @PathVariable String topic, @RequestBody ConfigChangeRequest request) {
        return configs.preview(clusterId, topic, request);
    }

    @HeldResponse
    @PostMapping("/topics/{topic}/config:apply")
    public ConfigPreview applyConfig(
            @PathVariable UUID clusterId, @PathVariable String topic, @RequestBody ConfigChangeRequest request) {
        return configChanges.apply(clusterId, topic, request);
    }

    @GetMapping("/acls")
    public AclPage acls(
            @PathVariable UUID clusterId,
            @RequestParam(required = false) String principal,
            @RequestParam(required = false) String resource,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return acls.list(clusterId, principal, resource, cursor, limit);
    }

    @PostMapping("/acls:preview")
    public AclPreview previewAcls(@PathVariable UUID clusterId, @RequestBody AclChangeRequest request) {
        return acls.preview(clusterId, request);
    }

    @HeldResponse
    @PostMapping("/acls:apply")
    public AclResult applyAcls(@PathVariable UUID clusterId, @RequestBody AclChangeRequest request) {
        return aclChanges.apply(clusterId, request);
    }

    @GetMapping("/transactions")
    public TransactionPage transactions(
            @PathVariable UUID clusterId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return transactions.list(clusterId, cursor, limit);
    }

    @PostMapping("/transactions/{id}:abort-preview")
    public AbortPreview previewAbort(@PathVariable UUID clusterId, @PathVariable String id) {
        return transactions.abortPreview(clusterId, id);
    }

    @HeldResponse
    @PostMapping("/transactions/{id}:abort")
    public AbortResult abort(@PathVariable UUID clusterId, @PathVariable String id, @RequestBody AbortRequest request) {
        return aborts.abort(clusterId, id, request.etag());
    }

    /** Finds the hanging transactions of the cluster or of one topic, on demand: it reads every partition in scope. */
    @PostMapping("/hanging-transactions:find")
    public HangingPage findHanging(
            @PathVariable UUID clusterId,
            @RequestParam(required = false) String topic,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return hanging.find(clusterId, topic, cursor, limit);
    }

    @PostMapping("/hanging-transactions:abort-preview")
    public HangingAbortPreview previewHangingAbort(
            @PathVariable UUID clusterId, @RequestBody HangingAbortRequest request) {
        return hanging.abortPreview(clusterId, request.topic(), request.partition(), request.producerId());
    }

    @HeldResponse
    @PostMapping("/hanging-transactions:abort")
    public HangingAbortResult abortHanging(@PathVariable UUID clusterId, @RequestBody HangingAbortRequest request) {
        return hangingAborts.abort(
                clusterId, request.topic(), request.partition(), request.producerId(), request.etag());
    }
}
