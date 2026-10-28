---
title: MCP 服务器
description: Rahdar 支持 Model Context Protocol，让助手在与人完全相同的授权与审计链路下，针对你真实的集群作答。
---

# MCP 服务器

Rahdar 支持 [Model Context Protocol](https://modelcontextprotocol.io)，因此助手可以针对你真实的集群回答*"为什么 `ORDERS.DLQ` 堆积了"*，而不是靠猜。

它暴露的是**意图化**工具——`diagnose`、`message_action`、`artemis_config_change`——而不是 REST API 的镜像。镜像会把模型的上下文耗在管道细节上，再把诊断工作丢回给它；这里的工具是按问题的形状设计的（[ADR-0045](/reference/adr/0045-mcp-server-is-a-capability-surface)，英文）。

## 获取密钥

登录 → 头像菜单 → **Account** → **API keys** → **New key** → 选择过期时间、它携带的范围与权限，以及（可选）它可以调用的 **MCP 工具** → 复制。密钥只显示一次。

- **过期。** 每把密钥都会过期，最长为安装设定的最大寿命（默认 90 天，*Settings → API tokens → Maximum lifetime*）。调低最大寿命会立刻缩短已有密钥。
- **工具。** 限定了工具的密钥只会看到这些工具以及 `rahdar_help`；其他工具既不会列出也不会被描述，调用它们得到的回答与调用不存在的工具完全相同。
- **轮换。** **Rotate** 会签发新密钥，旧密钥在轮换重叠期内（默认 24 小时）继续可用，因此可以不中断地更新助手的配置。密钥保留原有权限和过期时间。
- **限流。** 每把密钥每分钟最多 600 个请求、同时最多 8 个，每个用户所有密钥合计每分钟 1,200 个。每个响应都带有 `RateLimit-Limit`、`RateLimit-Remaining` 和 `RateLimit-Reset`，超出的请求得到 `429` 和 `Retry-After`。
- **用量** 按天显示每把密钥的请求数，以及被拒绝、被限流和失败的数量。拥有 *See and revoke every user's API tokens* 权限的管理员可以在 **Admin → API keys** 中看到所有密钥，长期未用的会被标记，任何密钥都可以吊销（[ADR-0136](/reference/adr/0136-token-rotation-lifetime-cap-limits-and-usage)，英文）。

## 接入

`POST /mcp`，与界面同源，密钥作为 bearer token。

```json
{
  "mcpServers": {
    "rahdar": {
      "type": "http",
      "url": "https://rahdar.example.com/mcp",
      "headers": { "Authorization": "Bearer as_..." }
    }
  }
}
```

不用客户端也能冒烟测试：

```bash
curl -s https://rahdar.example.com/mcp \
  -H "Authorization: Bearer as_..." \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

## 都有什么

| 类型 | 名称 | 用途 |
|---|---|---|
| Tool | `rahdar_help` | 目录本身：每个工具、它的姿态与参数 |
| Tool | `diagnose` | 一个集群（每个节点的 HA 角色、脑裂、复制延迟、正在触发的告警）或一个订阅的全链路 |
| Tool | `list_resources` | 按 `role` 列出目的地、订阅、路由、连接、通道、消费者与生产者，每行带有 id 与 kind |
| Tool | `metric_series` | 单个指标的分桶时间序列 |
| Tool | `artemis_config_diff` | 每个节点的配置与多数值的比较：发生漂移的键和与多数不同的节点 |
| Tool | `artemis_config` | 集群的声明、每个节点的漂移、`broker.xml` 片段，或历史应用 |
| Tool | `browse_messages` | 订阅的消息头，或按 id 取单条消息体 |
| Tool | `trace_request_reply` | 流、延迟与超时统计、已配置的预期 |
| Tool | `activity_log` | Broker 事件，或 Rahdar 自身的审计链路 |
| Tool | `message_action` | 移动 / 重投 / 删除 / 过期 / 清空 |
| Tool | `resource_lifecycle` | 创建、更新、暂停、恢复或销毁订阅或目的地 |
| Tool | `artemis_divert_lifecycle` | 创建或删除 divert |
| Tool | `artemis_config_change` | 声明一份配置，或以金丝雀优先、按 id 确认危害的方式应用它 |
| Tool | `connection_action` | 关闭连接、通道、消费者或某个目的地的全部消费者 |
| Tool | `send_message` | 投递一条消息 |
| Tool | `kafka_list_groups`、`kafka_describe_group` | 消费组及其状态、成员与滞后；单个消费组的分区与成员 |
| Tool | `kafka_preview_offset_reset`、`kafka_reset_offsets` | 一次重置会移动什么，以及按预览执行的重置本身 |
| Tool | `kafka_topic_config`、`kafka_set_topic_config` | 主题的各项设置及其来源，以及修改它们 |
| Tool | `kafka_list_acls` | 集群的 ACL |
| Tool | `kafka_abort_transaction` | 在预览之后中止一个事务 |
| Tool | `setup_review` | 集群最近一次的配置评审 |
| Tool | `alert_rule` / `rahdar_setting` | 告警规则；运维设置 |
| Resource | `rahdar://clusters`、`rahdar://permissions`、`rahdar://tools` | 这把密钥能看到什么、能做什么 |
| Resource | `cluster://{id}/topology`、`cluster://{id}/capabilities`、`cluster://{id}/nodes/{nodeId}/settings` | 节点；连接支持什么，以及启用其余部分所需的 `broker.xml`；单个节点的生效设置 |
| Prompt | `triage_cluster`、`investigate_subscription`、`before_you_purge`、`tune_scrape_load` | 运行手册 |

## 资源、角色与系统

工具用资源的**规范 id** 指代资源，例如 `artemis.queue/orders`，也就是 `list_resources` 在每一行返回的 `id`，连同系统给它的 `kind` 与 `kindLabel`。参数按资源的角色命名：`subscription`、`destination`、`resource`（生命周期的目标）和 `target`。`list_resources` 接受要列出的 `role`——`destination`、`subscription`、`route`、`connection`、`channel`、`consumer` 或 `producer`——以及可选的某个 `kind`。

只为某一个 Broker 系统存在的工具以该系统的 id 为前缀：`artemis_config`、`artemis_config_change`、`artemis_config_diff`、`artemis_divert_lifecycle`、八个 `kafka_*` 工具，以及 `setup_review`。只有能读取该系统某个集群的密钥才会被提供这些工具；对其他系统的集群调用，会得到一个说明该集群运行哪个系统的工具错误。

## 安全契约

一个握有管理 API 的助手，正是安全模型必须写得枯燥而明确的地方。这里就是：

- **密钥永远不会超过它的所有者。** 每次调用都会与所有者*当下*的授权取交集，所以收紧一个人的权限会立刻收紧他的所有密钥（[ADR-0046](/reference/adr/0046-mcp-authenticates-with-existing-api-tokens)，英文）。
- **变更默认 dry-run。** 真正的破坏性执行还额外要求 `confirm` 等于订阅或目的地自身的名称——这与批量上限的 `override` 是两个不同的问题，`confirm` 永远无法满足 `override`。
- **Kafka 的变更先预览，再被挂起。** `kafka_preview_offset_reset` 返回每个分区会跳过或重读什么、谁还占着这个消费组，以及一个 `etag`；`kafka_reset_offsets`、`kafka_set_topic_config` 与 `kafka_abort_transaction` 只会按其预览的 `etag` 执行，状态变动后会因过期而被拒绝，并且在审批闸门开启的地方被挂起等待批准。与所有变更类工具一样，安装设为只读时它们会被隐藏。
- **每次调用都会被审计**，读取也不例外：以 `MCP_TOOL_CALL` 记在所有者名下并附上密钥名称（`ada [token: laptop-agent]`），写明工具、集群和结果。变更写下的审计行（包括 dry run）都挂在它下面（[ADR-0137](/reference/adr/0137-one-gate-on-the-mcp-transport)，英文）。
- **整个安装可以设为只读。** *Settings → MCP server → Read-only* 会对所有密钥隐藏并拒绝一切变更类工具，无论 `confirm` 为何。单把只读密钥就是只被授予读权限的密钥。
- **对于密钥没有授权的集群**，返回的是*"不存在这样的集群，或这把密钥对它没有授权"*——既不点名缺了哪项权限，也不确认这个 id 是否存在。

## 发现机制

`rahdar_help` 是一个工具，而不是一个可选资源；并且由同一份目录生成 schema、帮助文本、资源与服务器说明——所以它们不可能相互矛盾（[ADR-0054](/reference/adr/0054-mcp-discovery-is-a-tool-not-a-resource)，英文）。
