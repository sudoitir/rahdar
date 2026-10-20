---
title: 配置
description: Rahdar 读取的环境变量、哪些是必需的，以及它维护的两个配置平面。
---

# 配置

## 环境变量

| 变量 | 必需 | 说明 |
|---|---|---|
| `RAHDAR_DB_URL` | 是 | `jdbc:postgresql://host:5432/rahdar` |
| `RAHDAR_DB_USER` / `_DB_PASSWORD` | 是 | — |
| `RAHDAR_SECRET_KEY` | 使用 `env` 提供者时 | 保护所有已存储机密的密钥。必须是**恰好 32 字节**的 Base64，否则应用不会启动：`openssl rand -base64 32`。要保存多个版本，参见[机密与密钥轮换](#机密与密钥轮换) |
| `RAHDAR_CONFIG_ENCRYPT_KEY` | 否 | 用于解密 `rahdar_config_property` 中存放的 `{cipher}` 值。这是与 `RAHDAR_SECRET_KEY` **不同**的一把密钥——不要复用 |
| `RAHDAR_PUBLIC_URL` | 使用通行密钥时 | 用户访问 Rahdar 的地址，例如 `https://rahdar.example.com`（属性 `rahdar.public-url`，原为 `rahdar.alerting.public-url`）。设置后，告警通知会链接回对应集群的告警页。**通行密钥**也绑定到它：未设置时，用户仍可使用验证器应用，但不能使用通行密钥，账户页会提示这一点。通行密钥归属于它的主机，因此更改主机（而不只是端口或路径）会使按旧主机注册的所有通行密钥失效；恢复码与验证器应用不受影响。若它不是 `http` 或 `https` 地址，启动会失败 |
| `RAHDAR_IDENTITY_LOCAL_RECOVER` | 否 | **应急恢复。** 填一个本地用户名。启动时 Rahdar 会解锁该账户，移除其验证器应用、通行密钥、恢复码与受信任设备，吊销其 API 令牌，要求下次登录时修改密码，终止其所有会话，并把操作写入审计记录、把警告写入日志。适用于唯一的管理员同时丢失了设备和恢复码的情况。**重启后请删除它**，否则下次重启会再次恢复该账户；用户名不存在时只会记录一条错误，不做任何更改。管理员用当前密码登录、设置新密码并重新注册验证方式 |
| `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` | 反向代理位于私有网段之外时 | 匹配你的反向代理地址的正则表达式。只有这些地址可以通过 `X-Forwarded-For` 设定客户端地址；登录限制与审计链路都使用该地址。默认信任回环地址、`10/8`、`172.16/12`、`192.168/16` 与 `fc00::/7`。切勿留空：那会信任所有客户端 |
| `JAVA_OPTS` | 否 | 默认为 `-XX:MaxRAMPercentage=50` |

## 机密与密钥轮换

每个已存储的机密都用各自随机生成的数据密钥加密，而数据密钥由带版本号的**密钥加密密钥**（KEK）包裹。这些机密包括 Broker 与桥接凭据、通知渠道机密、插件机密，以及治理功能封存的消息原文。KEK 由密钥提供者提供。提供者在启动时选定一次，没有回退：如果提供者无法给出有效密钥，Rahdar 不会启动，并会指明是哪个提供者。

### 提供者

`rahdar.secrets.provider` 选择其中一个。在环境变量中，短横线和点号都变成下划线，因此 `rahdar.secrets.vault.uri` 对应 `RAHDAR_SECRETS_VAULT_URI`。

| 提供者 | 密钥存放位置 | 设置（方括号内为默认值） |
|---|---|---|
| `env`（默认） | `RAHDAR_SECRET_KEY`：单个 Base64 密钥（版本 1），或用 `1=<b64>,2=<b64>` 表示多个版本 | 无 |
| `file` | 一个目录，内含名为 `kek-<n>` 的文件（32 字节的 Base64，`n` 为版本号）。挂载的 Kubernetes Secret 卷符合此格式 | `rahdar.secrets.file.directory` |
| `vault` | HashiCorp Vault KV 版本 2。每个有效 KV 版本的 `kek` 字段就是一个密钥版本 | `rahdar.secrets.vault.uri`、`.mount` [`secret`]、`.path`、`.oidc-path` [默认与 `.path` 相同]、`.authentication` [`token`；或 `approle`、`kubernetes`]、`.token`、`.role-id`、`.secret-id`、`.kubernetes-role`、`.kubernetes-token-path` [`/var/run/secrets/kubernetes.io/serviceaccount/token`] |
| `kubernetes` | 一个含 `kek-<n>` 键的 Kubernetes Secret，通过 API 服务器并以 Pod 的服务账号读取 | `rahdar.secrets.kubernetes.secret-name`、`.namespace` [Pod 自身所在的命名空间]、`.api-url` [`https://kubernetes.default.svc`]、`.token-path` [`/var/run/secrets/kubernetes.io/serviceaccount/token`]、`.ca-path` [`/var/run/secrets/kubernetes.io/serviceaccount/ca.crt`] |

`kubernetes` 提供者的服务账号需要对该 Secret 拥有 `get` 权限。

每个密钥都必须是恰好 32 字节的 Base64。密钥的版本就是其名称中的数字（`kek-2`、环境变量中的 `2=`，或 Vault 中的 KV 版本）。

### Vault 默认只保留少量版本

KV 版本 2 默认只保留 **10 个版本**，写入第 11 个时会删除最旧的版本，而且每次写入都算，哪怕只是修改了 `oidc-client-secret`。丢失一个仍有机密在使用的版本，这些机密就无法再读取。因此：

- 把该路径的 `max_versions` 设得足够大（例如 `vault kv metadata put -max-versions=0 secret/rahdar`，`0` 表示不限制）。
- 用 `rahdar.secrets.vault.oidc-path` 把 `oidc-client-secret` 放在单独的路径上，这样修改它不会给密钥路径增加版本。
- 不要删除或销毁仍在使用的版本。若有机密使用某个版本而提供者已没有它，**设置 → 安全** 会把它列为缺失，日志中也会警告该版本及其数量。

### OIDC 客户端密钥

提供者同样负责提供 OIDC 客户端密钥，名称为 `oidc-client-secret`：即同名的文件或 Kubernetes Secret 键、Vault 中 `.oidc-path` 最新版本的 `oidc-client-secret` 字段，或在 `env` 提供者下的 `RAHDAR_OIDC_CLIENT_SECRET`。只在这里配置，不要在别处配置：提供者不是 `env` 时，在 Rahdar 自己的 OIDC 设置里写入客户端密钥会报错。

### 轮换密钥

轮换是在线进行的：无需停机就能把所有机密迁移到新的密钥版本，并且全程不会读取任何明文机密。

1. 在提供者中添加新版本（`kek-2`、`1=…,2=…`，或新的 KV 版本）。**保留旧版本。**
2. 在 **设置 → 安全** 中选择 **轮换密钥**。这需要 `settings:write` 权限以及最近一次登录。等价的接口是 `POST /api/v1/settings/secrets/rotations`。
3. 新的机密立即使用新版本，后台任务分批重新包裹其余机密。观察轮换直至状态为 **Succeeded**：启动后它至少会等待 30 秒，让每个副本都获知新版本；期间重启 Rahdar 是安全的。
4. 只有在此之后才从提供者中移除旧密钥。**安全** 页面会统计每个版本下的行数，因此你能看出旧版本何时不再被使用。

如果轮换失败（错误信息会指明存储和行），请在提供者中保留旧密钥，排除原因后再次启动：它只会重新包裹仍处于旧版本下的机密。过早移除旧密钥会使其下的机密无法读取，唯一的补救是重新录入。

### 更换提供者

1. 把当前密钥放入新提供者，版本号**不低于**当前版本。
2. 修改 `rahdar.secrets.provider`（及其设置）并重启。只有新提供者持有当前版本时 Rahdar 才会启动。
3. 在新提供者中添加更新的版本并按上文轮换到它。之后才能停用旧提供者。

### 从早期版本升级

早期版本存储的机密会在首次启动时被丢弃，因为存储格式已变更。请重新录入 Broker 凭据（集群与桥接）、通知渠道机密和插件机密。此前封存的治理原文不再可用。其他内容不受影响。

### 脱敏

密码、令牌、API 密钥、Bearer 值、URL 中的用户信息和私钥，会在日志、审计参数、错误响应以及 broker.xml 导出中被替换为 `[redacted]`，即使机密经由 Rahdar 未预料到的途径出现也一样。Rahdar 只向控制台输出日志。

## 两个平面

配置被刻意拆成两半，划分依据是"谁在什么时候改这个值"。

**运维平面**——`rahdar_setting`——存放运维人员在 Rahdar 运行期间调整的内容：轮询节奏、保留窗口、批量操作上限、SQL 控制台的成本上限。改动即时生效、写入审计，且无需重启。每一个可由设置调节的调度都是一个会重新读取配置的触发器，而不是固定注解，这正是节奏变更能立刻生效的原因。

**部署平面**——Spring Cloud bootstrap 属性——存放属于这次部署的内容：数据源、密钥、OIDC issuer。这里的值可以以 `{cipher}` 形式存储，并由 `RAHDAR_CONFIG_ENCRYPT_KEY` 解密。系统中没有配置中心。

原因见 [ADR-0047](/reference/adr/0047-two-configuration-planes)，调度如何感知设置变更见 [ADR-0048](/reference/adr/0048-settings-driven-dynamic-schedules)（英文）。

## 登录与会话

在 **设置 → 会话** 与 **设置 → 密码登录** 下，无需重启即可调整：

| 设置 | 键 | 默认值 | 含义 |
|---|---|---|---|
| 空闲超时 | `security.session.idle-timeout` | `30m` | 会话在用户没有任何操作的情况下可持续多久。只有会更改内容的请求，以及控制台在用户点击或按键后一分钟内发出的请求才算作操作；轮询与实时流不算，因此开着不管的标签页会被登出。需要保持登录的脚本可发送 `X-Rahdar-Activity: 1` |
| 会话绝对时长 | `security.session.absolute-lifetime` | `12h` | 登录后多久会话必定结束，无论多活跃 |
| 密码最小长度 | `identity-local.password.min-length` | `12` | 新的本地密码至少需要的字符数 |
| 泄露密码检查 | `identity-local.password.breach-lookup` | 关闭 | 一个开关。开启后，新的本地密码还会与在线的泄露密码服务比对：只有其 SHA-1 的前五个字符会离开 Rahdar，查询失败则放行该密码。始终使用离线的 10 万个最常见密码列表 |
| 受信任设备有效期 | `identity-local.mfa.trusted-device-lifetime` | `30d` | 用户在登录时完成第二验证后，可以信任当前浏览器：在此期间，从该浏览器登录只需密码。设为 `0` 则关闭受信任设备：不再提供该选项，已有的受信任设备也会被忽略。需要再次确认身份的操作仍然需要第二验证；账户因别处的失败而被锁定时，其主人仍可在受信任设备上登录。修改密码、重置账户的验证方式、停用账户都会撤销其受信任设备 |

时长可写作 `30m`、`12h`，或 ISO-8601 形式（`PT30M`）。用户可在 **账户 → 会话** 中查看自己在哪里登录并结束其中任意会话；管理员可在 **管理 → 用户 → 会话** 中对任意用户做同样的事。见 [ADR-0145](/reference/adr/0145-session-lifetimes-and-session-management)（英文）。

## 数据库

PostgreSQL，模式由 Liquibase 拥有，并在启动时迁移。Rahdar 在启动时会针对迁移后的模式校验映射，而不是生成 DDL，因此漂移的模式会大声失败，而不是悄悄出错。

Postgres 拥有配置、用户与审计链路。来自 Broker 的表——`subscription_snapshot`、`metric_sample`——是**可丢弃的缓存**：丢了它们只会损失历史，绝不会损失真相。

## Broker 连接

通过界面注册，而不是通过环境变量。一个连接保存种子管理端点与凭据，并加密存储。主传输是 Jolokia HTTP；Artemis Core 客户端是第二通道，用于通知与忠实的消息 I/O，依赖它的功能会以它是否可达为门控——而且是可见地门控，并附上可以启用它的 `broker.xml`。

见 [ADR-0002](/reference/adr/0002-broker-transport-and-capability-model)（英文）。
