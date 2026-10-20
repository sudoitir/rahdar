---
title: SQL 控制台
description: 用 SQL 查询整个 Artemis 集群中的消息——包括 JMS selector 看不到的消息体——并在执行之前就看到这条查询的代价。
---

# SQL 控制台

![在 SQL 控制台中查询集群里的每一个队列，然后跟随实时 tail](/img/sql-console.gif)

运维人员真正带着来的问题是：*"订单 4471 去哪了？"*。Artemis 回答不了它。它唯一的服务端过滤器是 JMS selector，只能看到消息头与应用属性——**看不到消息体**——而且一次只作用于一个节点上的一个队列。

SQL 控制台能回答它：一条查询，覆盖集群中的每一个队列。

```sql
SELECT * FROM "ORDER.*"
WHERE body->>'orderId' = '4471'
LIMIT 50
```

## 真正的 SQL 语法，很小的子集

只有 `SELECT`。一个 `FROM`。`WHERE`、`ORDER BY`、`LIMIT`。没有 join、没有子查询、没有 union、没有 CTE——并且完全无法表达任何变更操作。

采用真实 SQL 语法是刻意的：编辑器现成的语法、高亮与补全无需改造即可工作。查询会被解析成 AST，并逐个节点类型对照固定的列目录做校验，默认拒绝。没有任何校验是靠对查询文本做模式匹配完成的——用正则断言"不含 `DROP`"正是这件事最常见的错误做法。

队列名始终使用双引号——`ORDER.IN` 既是保留字又含点号——通配符用的是 Artemis 的而不是 SQL 的：`*` 匹配一级（以点分隔），`#` 匹配多级。

## 查询从哪里读

Schema 限定符在查询文本自身中选择后端，因此一条被粘贴进工单的查询仍然说明了它读自哪里。

| | |
|---|---|
| `FROM broker."Q"` | 活着的 Broker。当前真相。 |
| `FROM index."Q"` | 历史索引——仍保留已被消费掉的消息，且只覆盖你订阅过的队列。 |
| `FROM "Q"` | 由规划器选择，并在计划中说明选了哪一个。 |

## 一个谓词的代价——在你执行之前

这是最值得了解的部分。每一个顶层 `AND` 合取项都会被分类：

| 类别 | 含义 |
|---|---|
| **Target** | 决定读取哪些队列。不花代价。 |
| **Pushdown** | 变成 JMS selector，由 Broker 过滤，Rahdar 根本看不到不匹配的消息。不花代价。 |
| **Scan** | Rahdar 必须检查 Broker 返回的每一条消息。 |

消息头与应用属性可以下推。消息体永远不行——除了 Rahdar 没有别的东西能读它。编辑器下方的**成本行**会在**执行之前**用一句话告诉你查询属于哪一类（Broker 过滤、扫描、索引、无成本）；无法估算时它写明“不可用”及原因，绝不显示为零；超过配置的成本上限时，查询会被拒绝，并给出估算值、上限，以及缩小范围的提示。是拒绝而不是截断：截断后的结果一眼看去与完整结果无法区分，而事故中的运维人员会把它读成"不存在"。

::: tip 一个值得明说的陷阱
一个单独看是免费的谓词，一旦与消息体谓词处在同一个 `OR` 中就不再免费。下推 `OR` 的一侧会缩小另一侧所能看到的集合——因此只要析取式中含有一个 scan，整个析取式都会被扫描。在这个领域里，这是唯一一种会产生**静默错误答案**而非仅仅变慢的错误。
:::

## 编写、运行与取消

编辑器与结果位于同一个工作区。拖动两者之间的分隔条，或聚焦它后按 **↑**、**↓**（每步 5%，按住 **Shift** 为 10%），**Home** 与 **End** 取最小与最大的编辑器。最小的编辑器仍显示三行查询及其下方的成本行。Rahdar 会在此浏览器中记住这个分割。

**运行**与**取消**并排放在工作区上方的 Query 工具栏中，因此无论编辑器多小都始终可见。**Ctrl .**（Mac 上为 **⌘ .**）可在编辑器内或页面任何位置取消。取消会关闭流，从而在服务端释放该查询并停止所有 Broker 读取。已到达的行保留在屏幕上，控制台会说明查询已取消，且这些行并非完整答案。取消实时 tail 即停止它。**Escape** 绝不会取消：它依次关闭补全、折叠选区，然后把焦点移出编辑器，移到 Query 工具栏。

语法错误会标记在编辑器中出错的词元上，并在旁边与成本行中给出原因。**F8** 跳转到它，**Ctrl Shift M** 列出所有诊断。被拒绝或失败的查询会以错误的形式给出原因与下一步，被拒绝时还会附上估算值与被拒绝所依据的上限。

结果使用与其他所有视图相同的表格。其**列**菜单可以显示或隐藏列，并通过“上移”与“下移”按钮调整顺序，选择会保存在此浏览器中。标识一行的消息始终排在第一位。

## 列

可用作 selector（免费）：`priority`、`durable`、`timestamp`、`expiration`、`size`、`jmsType`、`correlationId`、`groupId`、`userId`，以及以 `props.<名称>` 形式访问的任意应用属性。

Target（免费）：`subscription`、`destination`、`node`。

Scan：`body`、`messageId`、`messageType`、`replyTo`，以及仅索引可用的 `observedAt`、`lastSeenAt`、`origin`、`orig_destination` 与 `sourceMessageId`。

在索引上，`MATCH (body) AGAINST ('terms')` 是对已存储消息体的全文检索，走 GIN 索引而非扫描；`ORDER BY match_rank` 按匹配程度排序。引号短语、`-排除` 和 `or` 的行为与搜索框一致。二进制消息体不建全文索引；消息体为文本的字节消息与其他消息一样可被检索。

消息体中的 JSON 字段写作 `body->>'orderId'`。该方言只接受 `now()`、`lower()`、`upper()` 这三个函数，以及相对时间中的 `interval`。相对时间会经由每个节点实测的时钟偏移做归一化，因此时钟有偏差的 Broker 也能给出正确答案。

## 示例

```sql
-- 最便宜：没有任何谓词，Broker 返回它的头部页。
SELECT * FROM "ORDER.IN" ORDER BY timestamp DESC LIMIT 100;

-- 两个谓词都变成跨通配符的 selector。仍然免费。
SELECT * FROM "ORDER.*" WHERE priority > 4 AND durable = true LIMIT 200;

-- 应用属性同样可用作 selector。
SELECT * FROM "ORDER.IN" WHERE props.tenant = 'acme' LIMIT 200;

-- 一次扫描。先用一个消息头谓词把范围缩小。
SELECT * FROM "ORDER.IN" WHERE body LIKE '%4471%' LIMIT 50;

-- 一个时间窗口，已量化且已校正时钟偏差。
SELECT * FROM "ORDER.*"
WHERE timestamp > now() - interval '2 hours'
ORDER BY timestamp DESC LIMIT 500;
```

## 可选的索引

消息索引**按队列选择性开启**，受保留期约束，且可丢弃。它的存在是为了能对一条已经被消费掉的消息提问——而 Broker 对此已经（正确地）一无所知。在访问控制与删除的意义上，它被当作留存的载荷对待；丢掉它只会损失历史，绝不会损失真相。

索引有两种填充方式，它们的声明并不相同：

| 模式 | 一行意味着什么 |
|---|---|
| **采样** | 一次轮询在这个队列上看到了这条消息。在两次轮询之间到达又被消费掉的消息从未被记录。 |
| **捕获** | 这个 address 路由了这条消息。中间是否有人消费掉它没有区别。 |

捕获会改变 broker 的路由，因此它是一个需要单独权限的显式动作，也有自己的页面：[消息捕获](/zh/guide/message-capture)。

## 实时 tail

Tail 是**轮询**，绝不是消费。它不能改变任何东西，也不会和你的消费者争抢。UI 会永久声明这一点，因为一个读起来像完整抓取的采样式 tail，会让人得出"这条消息从未发出过"的结论。

## 对结果采取行动

不行，这是刻意的。对结果行采取行动会回到常规的消息操作路径，那里已经带有 dry run、批量上限与审计记录——通往破坏性动作的第二条路径，就是第二个把安全契约做错的地方。

## 相关决策（英文）

- [ADR-0058](/reference/adr/0058-sql-console-query-model)——方言、AST 校验、谓词拆分
- [ADR-0059](/reference/adr/0059-message-index-is-opt-in-and-disposable)——索引
- [ADR-0060](/reference/adr/0060-sampled-tail-is-not-a-capture)——tail
