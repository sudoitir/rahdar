---
layout: home
title: Rahdar
titleTemplate: 一个控制台，管理你的 Artemis 与 Kafka 集群
head:
  - - meta
    - name: description
      content: 面向 Apache ActiveMQ Artemis 与 Apache Kafka 的开源运维控制台：所有集群在同一个资产视图中，延迟如实标为估算值，破坏性操作先预览，人和助手的变更都要经过审批闸门。

hero:
  name: Rahdar
  text: 一个控制台，管理你的 Artemis 与 Kafka 集群
  tagline: 开源的消息 Broker 运维控制台。你运行的每一个集群都在同一个资产视图中，每个数字都说明它有多确定，任何破坏性操作在执行之前都会先让你看到它将做什么。
  actions:
    - theme: brand
      text: 快速开始
      link: /zh/guide/quickstart
    - theme: alt
      text: 交互式故事（英文）
      link: https://rahdar.dev/
    - theme: alt
      text: GitHub
      link: https://github.com/sudoitir/rahdar

features:
  - title: 跨系统的同一个资产视图
    details: Artemis 集群与 Kafka 集群并列在一起，健康状况按同一标尺判断。Artemis 队列与 Kafka 消费者组在同一张表中按积压排序，各自使用系统本来的名称。
    link: /zh/guide/
  - title: 如实的延迟
    details: Kafka 的延迟是估算值，并且明确标出。展开即可看到逐分区的明细，最慢的在前，并注明采样时间。读不到的数字显示为“不可用”并附原因，绝不显示为 0。
    link: /guide/kafka
  - title: 先预览，再执行
    details: 每一个破坏性调用都可以 dry run，并说明影响范围。偏移量重置会逐分区预览将跳过或重读多少记录；有成员的消费者组会被拒绝，并列出成员。确认方式是按住按钮直到填满。
    link: /guide/kafka#reset-offsets
  - title: 审批闸门
    details: 启用审批闸门后，重置、截断、中止事务等变更会等待第二个人批准，执行时再检查一次状态。每一次变更都在调用 Broker 之前写入审计。
    link: /guide/kafka
  - title: Artemis 的集群视角
    details: 主备拓扑，HA 角色在每个周期从每个节点轮询得到，绝不读取配置。所有节点的所有队列在一张表里，另有 Flow、对消息的 SQL 查询与 Broker 配置声明。
    link: /zh/guide/sql-console
  - title: 与人共用一套权限的 MCP 服务器
    details: 助手用不超过其所有者权限的 API 密钥读取你真实的集群。八个 kafka_* 工具的变更只针对它看过的预览执行，并在闸门开启时等待人来批准。
    link: /zh/guide/mcp
---

## 支持的系统

- **Apache ActiveMQ Artemis** 2.33.0 及以上。
- **Apache Kafka** 3.0 及以上，KRaft 与 ZooKeeper 模式。见 [Kafka 指南](/guide/kafka)（英文）与[支持的 Broker 版本](/guide/supported-versions)（英文）。
- **RabbitMQ**：即将推出，目前尚不支持。

Rahdar 通过开源的 Broker SDK [OpenBroker](https://github.com/sudoitir/openbroker) 与 Broker 通信，每个系统一个驱动。

## 一条命令装好

```bash
git clone https://github.com/sudoitir/rahdar && cd rahdar
just up          # 两个 Rahdar 副本 + HAProxy + Postgres，自动生成密钥，并固定到最新发布版本
```

你的 Broker 本来就存在：Rahdar 只负责纳管，不会去启动它们。在界面中用 Artemis 管理 URL 或 Kafka 引导服务器注册集群，保存之前会检查能否连到每一个 Broker。[完整快速开始 →](/zh/guide/quickstart)

::: warning Alpha 阶段
仍在积极开发中，功能尚未完备。已发布的镜像都是预发布的 dev 构建（`sudoit1/rahdar:dev`，暂无 `:latest`）。请预期会有破坏性变更。
:::

<small>Apache、Apache Kafka、Kafka、Apache ActiveMQ、ActiveMQ 与 Artemis 是 Apache 软件基金会的商标；RabbitMQ 是其所有者的商标。Rahdar 与它们没有关联，也未获得它们的认可。</small>
