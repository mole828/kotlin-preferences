# 评估：Cloudflare Worker 运行时

记录日期：2026-11-12。环境：Kotlin Toolchain 0.13.0，Ktor 3.6.0，sqlx4k 1.12.0。

## 背景

[ktor-cloudflare-worker](https://github.com/mole828/ktor-cloudflare-worker) 是一个独立原型：用 Kotlin/JS 把 Ktor 适配到 Cloudflare Workers 的 Fetch 接口，`FetchWorker.kt` 实现了一个 Ktor 引擎。评估想回答：它能否像 `jvm` / `linux` / `macos` 那样，作为 `backend/runtime/` 下的又一个运行时接入本项目。

## 结论

暂不接入。运行时本身接得上，过不去的是存储：本项目的事务约定需要交互式事务，而 Worker 上最顺手的 D1 不提供。

| 问题 | 结论 |
| --- | --- |
| 路由层能否编译到 JS | 能。`backendModule`、认证插件、`StatusPages`、SSE、JWT、PBKDF2 不用修改 |
| sqlx4k 能否在 JS 上使用 | 不能。1.12.0 只发布 JVM 与 Native 制品 |
| D1 能否实现 `Atomicity.atomic` | 不能。D1 自动提交，事务只有 `batch()`，语句必须事先全部给出 |
| 换成 Hyperdrive + PostgreSQL | 事务约定可以保留，但不支持 `LISTEN` / `NOTIFY`，仓库要用 JS 驱动另写一份 |
| Durable Object 的 SQLite | 有事务，但后端要按“每个房间一个对象”重新组织 |

因此当前不做改动：

- 不为接入 Worker 修改事务约定。“事务边界由用例决定、经协程上下文提供”（见 [transactions.md](transactions.md)）是本项目的核心倾向，Worker 只是可选的部署目标。
- 不做下文实验中的源码拆分。没有 JS 入口使用它时，它只是为假想扩展预留的结构。
- 原型继续独立存在，适合没有多步事务的服务：页面渲染、接口转发、webhook、单语句读写。

## 事务为什么是障碍

事务在这里是两件事，只有第二件过不去：

- **跨越多级**：路由的 `atomic`、`ChatService.join` 的 `atomic`、`SqlxChatRoomRepository.addMessage` 自己的 `withCurrent` 逐级参与同一个事务。这靠协程上下文传播，Kotlin/JS 上同样可用。
- **跨多次 await 保持打开**：这是存储的能力。`ChatService.join` 先插入成员关系，根据是否新增再决定写不写入群消息；`addMessage` 先取序号再写消息。两者都要在事务中途读到上一步的结果。

D1 的事务只有 `batch()`：一组事先准备好的语句整体提交或回滚，中途不能根据结果分支。要在 D1 上满足这些用例，只能把业务改写成“先算好所有语句，再一次提交”，存储方式会反过来决定 `modeling` 的写法。

## 实验：路由层编译到 JS

在仓库副本中修改 `backend`，没有改动本仓库：

```yaml
product:
  type: kmp/lib
  platforms: [jvm, js, linuxX64, linuxArm64, macosArm64]

aliases:
  - server: [jvm, linuxX64, linuxArm64, macosArm64]

dependencies:
  - ../modeling
  - $ktor.server.core
  - $ktor.server.contentNegotiation
  - $ktor.serialization.kotlinx.json
  - $ktor.server.statusPages
  - $ktor.server.sse
  - org.kotlincrypto.macs:hmac-sha2:0.8.0
  - org.kotlincrypto.random:crypto-rand:0.6.0

dependencies@server:
  - $ktor.server.cio
  - io.github.smyrgeorge:sqlx4k-postgres:1.12.0
```

- `src/` 保留 `Server.kt`（只含 `backendModule` 及其辅助函数）、`JwtTokens.kt`、`Pbkdf2PasswordHasher.kt`。
- `src@server/` 放 6 个依赖 sqlx4k 的文件（`SqlxAtomicity`、三个 `Sqlx*Repository`、`PgRoomSignals`、`QueryExecutor`），以及从 `Server.kt` 拆出的 `runBackend`：它用到 CIO、`runBlocking` 和 sqlx4k，`runBlocking` 在 JS 上不存在。

结果：

- `./kotlin build -m backend -p js` 通过，`modeling` 与 `backend` 的 JS 编译成功。默认 debug 变体只生成 `.klib`，没有链接。
- `./kotlin build -m jvm` 通过，拆分不影响现有 JVM 入口。
- Maven Central 上，Ktor 3.6.0 的 `ktor-server-core`、`ktor-server-cio`、`ktor-server-content-negotiation`、`ktor-server-status-pages`、`ktor-server-sse`、`ktor-serialization-kotlinx-json` 以及 kotlincrypto 的 `hmac-sha2` 0.8.0、`crypto-rand` 0.6.0 都有 JS 制品；`sqlx4k` 与 `sqlx4k-postgres` 1.12.0 没有。

`backendModule(chat, accounts, tokens)` 只依赖 `modeling` 的服务和 `JwtTokens`，与宿主无关；运行时接入需要的只是这次拆分，加上把 `FetchWorker` 放进一个 `js/app` 模块。

## 存储选项

- **D1**：自动提交，`batch()` 中的语句作为一个事务顺序执行，任一失败则整体回滚。没有可跨 await 的事务。
- **Hyperdrive + PostgreSQL**：SQL 和事务约定不变，数据库仍需在 Cloudflare 之外常驻。Hyperdrive 明确不支持 `LISTEN` / `NOTIFY`，推送只剩现有的定时唤醒（见 [push.md](push.md)）。三个 SQL 仓库要基于 JS 驱动再实现一份，可由对照测试守住两份实现的一致性。
- **Durable Object 的 SQLite**：`transactionSync` 的回调必须同步完成，`sql.exec` 不能执行 `BEGIN TRANSACTION`；另有可包含 await 的 `transaction()`。原子性和推送都可以按房间落在一个对象里，但这是围绕特定平台能力重新设计后端，而不是增加一个运行时。

## 未验证的部分

- `FetchWorker` 没有与 `backendModule` 一起链接，也没有在 workerd 中运行。SSE 长连接经过 Fetch 适配器是否逐条送达、断开时是否取消收集，都没有测试。
- Hyperdrive 的文档没有直接写明支持交互式事务，只建议“在显式事务中使用事务级 advisory lock”，由此推断支持，没有实测。
- `Pbkdf2PasswordHasher` 默认迭代 60 万次。纯 Kotlin/JS 的耗时没有测量；Workers 的 WebCrypto 对 PBKDF2 迭代次数是否仍有 10 万次上限，没有找到官方文档确认。
- 原型使用 Toolchain 0.12.2、Kotlin 2.4.0、Ktor 3.5.0，与本项目的版本不同，没有在本项目的版本下构建。

## 何时重新评估

- sqlx4k 发布 JS 制品，或出现可在 Worker 上使用的多平台 PostgreSQL 驱动。
- D1 提供可跨 await 的交互式事务。
- 有真实项目必须部署到 Worker。届时先做上面的源码拆分，再从存储选项中选择。

## 参考资料

- [D1 Database API](https://developers.cloudflare.com/d1/worker-api/d1-database/)
- [Hyperdrive 支持的数据库与特性](https://developers.cloudflare.com/hyperdrive/reference/supported-databases-and-features/)
- [Durable Object SQLite Storage API](https://developers.cloudflare.com/durable-objects/api/sqlite-storage-api/)
- [workerd #1346：PBKDF2 迭代次数上限](https://github.com/cloudflare/workerd/issues/1346)
