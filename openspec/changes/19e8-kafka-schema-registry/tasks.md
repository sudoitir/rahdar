## 1. Registry

- [x] 1.1 Store the registry on the cluster and the binding per topic side. The credential is sealed and is not returned.
- [x] 1.2 `schema:read` and `schema:write` are catalogue permissions. Viewer reads. Operator reads and writes. `destination:update` is not write.
- [x] 1.3 Preview and apply a registration, a compatibility change and a delete. A preview writes nothing. A stale etag, including after approval, writes nothing. A permanent delete says the schemas cannot be restored.
- [x] 1.4 Browse fills a decoded field. A tombstone is `no-schema-id`. A truncated body is not decoded. A body rule masks the decoded field.
- [x] 1.5 MCP tools call the same operations. A read-only token is refused before a hold.
- [x] 1.6 The registry screen, the binding form, and the decoded record on the message detail.
