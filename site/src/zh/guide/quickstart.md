---
title: 快速开始
description: 用已发布的镜像运行 Rahdar 与它的 Postgres，注册第一个集群并登录。
---

# 快速开始

Rahdar 需要两样东西：一个 PostgreSQL 数据库，以及能够访问你的 Broker 管理端点的网络连通性。你的 Artemis 集群本来就存在，通过界面注册即可——这里没有任何东西会启动一个 Broker。

::: warning Alpha 阶段
已发布的镜像都是预发布的 dev 构建（`sudoit1/rahdar:dev`，尚未发布 `:latest`）。版本之间可能出现破坏性变更。
:::

## 从克隆仓库开始，使用 `just`

[`just`](https://github.com/casey/just#packages) 是本仓库使用的任务运行器。单独执行 `just` 会分组列出全部任务。

```bash
git clone https://github.com/sudoitir/rahdar && cd rahdar
just up
```

`just up` 会先执行 `just setup`：在干净的检出上，它会写入 `deploy/compose/.env`，其中包含随机生成的 `SECRET_KEY` 与 `DB_PASSWORD`，并把 `RAHDAR_IMAGE` 固定到最新的发布标签。随后启动两个 Rahdar 副本、HAProxy 与 Postgres 并等待其就绪（`just up-single` 只启动一个副本，不含负载均衡）。打开 <http://localhost:8080>。

之后再次运行 `just setup` 即可把版本固定向前推进。

## 不使用 `just`

```bash
base=https://raw.githubusercontent.com/sudoitir/rahdar/main/deploy/compose
mkdir -p rahdar/compose rahdar/postgres && cd rahdar/compose   # the compose file mounts ../postgres
curl -sO "$base/compose.prod.yaml"
for f in postgresql.conf 10-pg-stat-statements.sql; do curl -s "${base%/compose}/postgres/$f" -o "../postgres/$f"; done
curl -s "$base/.env.example" -o .env   # 然后按"配置"一节编辑它
docker compose -f compose.prod.yaml --env-file .env up -d
```

或者，针对你已有的 Postgres 直接跑一个容器：

```bash
docker run -p 8080:8080 \
  -e RAHDAR_DB_URL=jdbc:postgresql://db:5432/rahdar \
  -e RAHDAR_DB_USER=rahdar \
  -e RAHDAR_DB_PASSWORD=... \
  -e RAHDAR_SECRET_KEY="$(openssl rand -base64 32)" \
  sudoit1/rahdar:dev
```

## 首次登录

用户名是 `admin`。针对空数据库的首次运行会生成密码，并**仅打印一次**到容器日志：

```bash
docker compose -f compose.prod.yaml logs rahdar | grep -A4 'Created administrator'
```

首次登录时会强制你设置新密码。如果在修改之前弄丢了这个初始密码，唯一的恢复办法是直接在 Postgres 中重置对应行——目前还没有找回密码的流程。

内置的 **ADMIN** 角色要求两步验证，所以设置好新密码后，Rahdar 会马上让你设置验证器应用（扫描二维码，再输入一个验证码确认）或通行密钥（passkey），并**仅显示一次**十个一次性恢复码。请妥善保存：设备丢失时，它们是你重新登录的办法。之后每次登录都会先要密码，再要一个验证码。

脚本或 `curl` 的登录方式相同。对已有验证方式的账户，`POST /api/v1/auth/login` 返回 `{"status": "SECOND_FACTOR_REQUIRED", …}`，随后用 `POST /api/v1/auth/second-factor` 提交 `{"totpCode": "123456"}`。尚未设置的账户用 `POST /api/v1/auth/mfa/totp` 开始设置（返回 `secret` 和 `otpauthUri`），再用 `POST /api/v1/auth/mfa/totp/confirm` 提交 `{"code": "123456"}` 确认，响应中包含恢复码。API 密钥不需要验证码。

## 注册一个集群

登录后进入 **Clusters → Register cluster**（或空概览页上的按钮）。注册是一个独立的页面，地址为 `/clusters/new`，可以直接链接：`/clusters/new?system=kafka` 会直接打开 Apache Kafka。先选择 Broker 系统：Rahdar 列出它拥有驱动的系统，之后只询问该系统的连接信息。**Check connection** 对你填写的这组信息通过之后，再填写集群的名称、描述与环境，**Register cluster** 才会可用。在此之前它保持禁用，并在旁边写明原因；检查之后只要有任何一项改动，它会再次禁用。

凭据以 `RAHDAR_SECRET_KEY` 加密存储，之后不会再显示。

### Apache Kafka

填写一个或多个 **bootstrap servers**，格式为 `host:port`，可用逗号、空格或换行分隔。端口默认为 9092，每个地址在输入框下方各显示为一项；带协议（`PLAINTEXT://k1:9092`）或带账号的地址会被拒绝，并说明要去掉什么。一个地址就够了：Rahdar 会向它询问其余的 Broker。

选择**安全协议**：明文、TLS、明文 SASL 或 TLS 上的 SASL。SASL 协议还要求选择**机制**：`PLAIN`、`SCRAM-SHA-256`、`SCRAM-SHA-512` 需要用户名与密码（要么都填，要么都不填），`OAUTHBEARER` 需要令牌。明文 SASL 会以明文传输凭据，表单会发出警告，但仍允许注册。TLS 协议可以指定 **TLS bundle**（Spring SSL bundle），用于信任库与客户端证书。主机名校验始终开启：证书没有写明其所通告地址的 Broker 会被拒绝。客户端 id 在 *Advanced* 中。

**Check connection** 报告 Kafka 版本（Broker 无法给出更多信息时显示为下限）与模式、每个 Broker 及其**通告**的地址和 Rahdar 是否在该地址连上了它、控制器仲裁（或其不可用的原因），以及该账号能做什么。检查失败时会写明原因与下一步：域名无法解析、连接被拒绝、连接超时、证书不受信任或与主机名不符、认证失败、账号已通过认证但无权描述集群（这与密码被拒绝不同），以及 Kafka 版本不受支持（低于 3.0）。

Bootstrap 服务器可能已经应答，而某个 Broker 通告了 Rahdar 无法到达的地址，例如只在集群内网才能解析的名称。此时检查会把该 Broker 及其通告地址列为未连上：Broker 的**通告监听器（advertised listeners）**必须能从 Rahdar 访问。如果只连上了部分 Broker，你仍可在看到警告（集群将显示为降级）后注册。

Kafka 集群只能注册一次，以其 Broker 报告的集群 id 为准。如果检查发现该集群已经注册（即使名称不同），会给出指向它的链接，且 **Register cluster** 保持禁用。

### Apache Artemis

只需提供一个种子节点的管理端点及其凭据；拓扑中的其余部分会从 Broker 自身发现。要提供多个种子，可在同一输入框中用逗号、空格或换行分隔多个 URL，每一个都会按 Rahdar 实际使用的形式显示出来。

如果某项能力缺失——Core 客户端不可达，或某个管理操作未暴露——Rahdar 会告诉你缺的是哪一项，并给出可以启用它的确切 `broker.xml` 片段，而不是把功能藏起来。

## 部署在反向代理之后

界面的实时流是 `GET /api/v1/stream` 上的 Server-Sent Events，Rahdar 前面的代理**绝不能对它做缓冲**：

- nginx —— 对该路径设置 `proxy_buffering off;`
- Apache —— 对该路径关闭输出缓冲
- Traefik —— 无需改动即可工作

否则拓扑图和队列表格只会在 5 秒轮询时更新，看起来就像一个半死不活的产品。

资源在一个 URL 段中以其 id 寻址，因此包含斜杠或百分号的名称会以 `%2F` 或 `%25` 到达
（`/api/v1/clusters/…/resources/artemis.queue/orders%252Feu`）。代理必须原样转发这些内容，
否则无法访问这类资源：

- nginx —— `proxy_pass` **不带** URI 部分（`proxy_pass http://rahdar:8080;`，而不是
  `http://rahdar:8080/`），这样请求行会原样转发
- Apache —— `AllowEncodedSlashes NoDecode` 与 `ProxyPass … nocanon`
- HAProxy 与 Traefik —— 无需改动即可工作

## 先拿一次性的 Broker 试试

如果你想在把它接到任何真实系统之前先看看效果，仓库自带一套开发栈：从源码构建 Rahdar，启动两对真实的 Artemis 主备节点，并灌入看起来像真实生产环境的流量——包括一个没有消费者、堆积持续增长的地址，一份真实的死信积压，以及一个被停掉的节点。

```bash
just dev-up                          # Postgres + 一对 Artemis + Rahdar
ADMIN_PASSWORD=… just demo           # 第二对节点，加上真实流量
```

`just dev-up` 打印的密码是一次性的。第一次运行时，种子脚本需要 `NEW_ADMIN_PASSWORD=…`（你选的新密码），它会替你改掉密码，并为 `admin` 设置其角色所要求的验证器应用，然后打印 `ADMIN_TOTP_SECRET`。之后再运行（包括 `just shots`）需要把 `ADMIN_PASSWORD` 设为新密码，并把 `ADMIN_TOTP_SECRET` 设为该密钥。

这套种子脚本不会直接写 `metric_sample` 或 `subscription_snapshot`——流量是通过 Broker 自带的 CLI 真实生产与消费的，所以图表是测量结果，而不是编造的。

## 接下来

- [配置](/zh/guide/configuration)——它读取的每一个变量
- [Broker 配置](/zh/guide/broker-configuration)——声明集群应当运行什么，并关闭首次启动时报告的能力缺口
- [SQL 控制台](/zh/guide/sql-console)——提出一个跨队列的问题
- [MCP 服务器](/zh/guide/mcp)——把同样的能力交给助手
