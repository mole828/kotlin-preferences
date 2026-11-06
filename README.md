# Kotlin Preferences

用于记录和实践项目技术倾向的 Kotlin 多模块项目。使用 Kotlin Toolchain 0.13.0（原 Amper），构建版本及校验和固定在根目录的 `kotlin` / `kotlin.bat` 中。

聊天功能作为示例，用来展示业务逻辑、界面、传输和存储各自放在哪里。项目重点是将业务模型、规则与用例集中在 `modeling`，让前后端开发者都能直接理解、审查和测试同一份业务逻辑。

这套结构基于一个前提：整个项目，从业务模型、前后端到部署，由一个人（借助 AI）负责。按康威定律，系统结构往往映射组织的沟通结构：前后端分属不同团队、各自发布时，双方需要一份语言无关的接口文档，并为对方的旧版本保留兼容，契约只能靠沟通和约定维持。当一个人负责全部环节时，这些协调成本不再存在，真正的风险变成“改了一处，忘了另一处”。因此本项目让前后端共同依赖 `modeling` 中的同一份 Kotlin 定义：请求、响应、业务失败都是共享类型，修改业务定义后，编译器会指出前端、后端和传输层所有受影响的位置，不需要靠人记住哪些地方要同步修改。类型安全的全栈 Kotlin 是这一结构的基础，而不是可替换的技术选择。反过来，这里不为非 Kotlin 客户端或前后端独立发版做额外设计；若项目的人员结构变为多团队分别负责，需要重新评估这些取舍。

## 模块与技术倾向

| 模块 | 技术 | 职责 |
| --- | --- | --- |
| `web` | Kotlin/JS、kotlin-react、Vite | 聊天界面：加入/离开房间、发送消息、历史记录与轮询更新 |
| `backend` | Ktor CIO、Kotlin Multiplatform | JVM、Linux Native、macOS Native 共享的 HTTP 路由、业务服务装配与 sqlx4k 持久化 |
| `jvm` | Kotlin/JVM | JVM 服务启动入口 |
| `linux` | Kotlin/Native，Linux x64 + ARM64 | Linux 原生服务启动入口 |
| `macos` | Kotlin/Native，macOS ARM64 | 本地开发用的 macOS 原生服务启动入口 |
| `modeling` | Kotlin Multiplatform，JVM + JS + Linux/macOS Native | 领域模型、业务规则与用例、仓库接口，以及共享 HTTP 契约 |

依赖方向为 `web → modeling ← backend ← jvm/linux/macos`。前后端共同依赖 `modeling` 中的业务定义，彼此不直接依赖。`backend` 是 `kmp/lib`，共享源码位于 `backend/src/`；三个应用模块位于 `backend/runtime/`，仅调用共享的 `runBackend()`；后端适配代码和各平台启动入口统一收在 `backend/` 目录下。

### 业务逻辑集中在 modeling

`modeling.ChatService` 注入 `MembershipRepository`、`ChatRoomRepository` 和 `Atomicity`，负责成员检查、入群消息和消息存取。`UserId` 与 `RoomId` 在构造时校验 1–80 字符，`MessageContent` 在构造时校验非空且最多 4000 字符，非法值无法进入业务代码。仓库只负责存取，不承载业务规则：只有房间成员能发送和拉取消息，这条规则由 `ChatService` 检查。service 与 repository 在启动时创建并长期复用。业务模块不依赖具体数据库或 sqlx4k；`Atomicity.atomic` 提供用例需要的原子执行能力，构造 service 时注入一次。

事务边界由用例的原子性要求决定，统一通过 `Atomicity` 声明在 `modeling` 中。后端路由不建立事务，只解析参数、调用业务并返回响应：

```kotlin
val request = call.receive<RoomMembershipRequest>()
chatClient.join(request.userId, request.roomId)
call.respond(HttpStatusCode.NoContent)
```

两个 SQL repository 接收默认 `QueryExecutor`，通过 backend 的扩展函数 `database.currentExecutor()` 获取执行器：有事务上下文时使用当前事务，没有时使用默认数据库执行器。SQL 失败直接向上抛出，不切换执行器重试。backend 的 `SqlxAtomicity` 接收同一数据库的 `Driver`，通过 sqlx4k 的 `TransactionContext.withCurrent` 复用或建立事务，仓库接口不负责管理事务。

`ChatService.join` 在同一个 `atomicity.atomic` 中完成加入房间和写入入群消息，即使调用方没有建立事务也不会降级为两次独立提交；有外层事务时参与该事务，不提前提交。入群消息写入失败时，成员关系一起回滚；重复加入不重复发消息。其他操作不需要跨语句原子性，直接使用默认执行器。发送和拉取先检查成员关系再读写消息，两步之间不加事务：用户恰好在两步之间离开，等同于离开前完成了这次操作。若离开房间将来带有清理等副作用，需要重新评估这一点。新增需要多步写入的用例时，在 service 中用 `atomicity.atomic` 包住这些步骤。成员表与消息表由各自 repository 管理，启动时建表。

临时组合多个业务调用、又需要原子性时（例如尚未沉淀为用例的换房间），路由可以使用与 `ChatService` 相同的 `Atomicity` 实例，而不是直接调用 sqlx4k：

```kotlin
val request = call.receive<SwitchRoomRequest>()
atomicity.atomic {
    chatClient.leave(request.userId, request.fromRoomId)
    chatClient.join(request.userId, request.toRoomId)
}
call.respond(HttpStatusCode.NoContent)
```

`join` 内部的 `atomic` 会参与这个外层事务，任一步失败时整体回滚。`call.respond` 放在 `atomic` 之外：事务提交成功后才响应，也避免网络写出期间占用事务。这种组合一旦成为稳定的业务规则，应移入 `ChatService`，路由恢复为单次业务调用。

每个 repository 只访问自己的表，不写跨表 SQL；跨 repository 的一致性要求只通过 `atomicity.atomic` 声明。`SqlxAtomicity` 依赖单库事务，若将来拆分存储，这些调用点就是需要重新设计一致性方案的位置。

当前按单数据库使用，事务生命周期沿用 sqlx4k 1.12.0：`withCurrent` 复用已有事务，不添加自定义 key 或事务传播规则。详见 [sqlx4k TransactionContext](https://github.com/smyrgeorge/sqlx4k#transactioncontext-coroutines)。

前端开发者可以直接阅读这些规则和流程，检查界面行为是否符合业务要求，并参与业务变更的审查。业务代码依赖仓库接口，测试时注入实际 repository，在嵌入式数据库上验证业务规则；仓库接口也为原型演示提供替换空间。

共享源码与实际执行位置分别由模块组织和运行时装配决定。当前 Web 通过 HTTP 调用后端，由后端执行 `ChatService` 中的业务逻辑与校验；前端能够检查或复用规则，服务端仍负责保证请求有效。

### 调用契约与平台适配

聊天的共用入口是 `modeling.ChatClient`。`ChatService` 实现该接口，后端路由只调用这个接口；`modeling.client.KtorChatClient` 也实现同一接口，集中管理 URL、JSON 和查询参数。Web 注入 `HttpClient(Js)` 和浏览器同源地址，调用共享客户端并管理界面状态。HttpClient 的创建与关闭由使用它的平台负责。后端使用 Ktor 的 `ContentNegotiation { json() }` 处理 JSON，通过 `StatusPages` 统一映射异常；路由直接调用 `receive<T>()` 和 `respond()`。

业务失败属于 `ChatClient` 契约的一部分，由可序列化的 sealed 类型 `ChatError` 表示，例如 `ChatError.NotInRoom`。两个实现都抛出携带它的 `ChatException(error)`，调用方不需要知道背后是本地 service 还是 HTTP，用 `when (e.error)` 处理。异常只负责中断流程，HTTP 只传输其中的 `ChatError`：后端按 `statusOf(error)` 选择状态码，以 `ChatError` 为声明类型返回 `{"type":"NotInRoom"}`；`KtorChatClient` 把响应体解码为 `ChatError` 后重新包装成 `ChatException`，不依赖状态码或提示文字。其他 HTTP 失败（参数无效、路由不存在、服务器错误）不属于业务结果，后端返回纯文本，客户端保留 Ktor 的 `ClientRequestException` / `ServerResponseException`。客户端对所有非 2xx 响应都尝试解码 `ChatError`，因此无论 `statusOf` 为某类失败选择 4xx 还是 5xx，都会还原为 `ChatException`。新增业务失败时，在 `ChatError` 中增加一个子类型；编译器会要求 `statusOf` 和各处 `when` 处理它，`KtorChatClient` 无需修改。

业务失败通过异常传递，而不是作为返回值。Kotlin 的设计建议通常是：可预期的失败用针对具体业务的 sealed 返回类型表达，异常留给程序错误。本项目选择异常有两个具体原因：`atomicity.atomic` 依靠异常触发回滚，若失败作为返回值，事务中的业务失败不会自动回滚，每个用例都要自行判断；用异常时，`ChatService` 的业务步骤是一条直线（检查成员、写入消息、返回结果），不必在每一步之后处理“失败则提前返回”。失败的种类仍由 `modeling` 中的 sealed 类型定义，异常只是传递方式。这是基于本项目事务机制的取舍，不是 Kotlin 的通用做法。

`ChatError` 分层表达失败：根接口圈定捕获范围，即 `ChatClient` 契约内的全部业务失败；中间层按失败的性质分类，目前只有 `ChatError.NotPermitted`（无权执行该操作），`NotInRoom` 属于这一类。中间层的名称明确说明发生了什么，让调用方据此判断如何处理，不使用 HTTP 概念；backend 的 `statusOf` 只按类别选择状态码（`NotPermitted` 对应 `403`），同一类别下新增具体失败时无需修改映射。调用方可以按类别处理，也可以像 Web 那样针对具体失败处理。出现其他性质的失败时再增加对应类别，例如“目标不存在”“与当前状态冲突”；若出现只关心某一业务区域（如成员关系）的处理方，再增加按区域划分的父接口，sealed interface 允许一个失败同时属于某个性质类别和某个业务区域。类别根据实际出现的失败划分，不预先设立。

Web 据此处理一种业务情况：同一昵称在另一个页面离开房间后，本页轮询或发送时收到 `ChatError.NotInRoom`，界面回到加入表单并提示重新加入。

Kotlin 源码统一由 Kotlin Toolchain 管理，通过 `project.yaml` 聚合各模块，各模块使用 `module.yaml` 声明产品类型和依赖。前端 React Kotlin 包装器通过 Maven BOM 统一版本；React 的 JS 运行时与 Vite 在 `web/package.json` 中声明。

## 构建与运行

macOS / Linux 使用 `./kotlin`，Windows 使用 `kotlin.bat`。包装脚本首次执行时会自动下载工具链和 Java 运行时，需要访问 JetBrains 和 Maven 仓库。

```sh
./kotlin show modules
./kotlin build
docker compose up -d
./kotlin run -m jvm
```

后端使用 Ktor 3.6.0 和 CIO 引擎，默认监听 `127.0.0.1:8080`。`GET /` 返回项目技术信息，`GET /health` 返回 `OK`。聊天记录与房间成员通过 sqlx4k 1.12.0 存入 PostgreSQL。根目录的 `compose.yaml` 提供本地 PostgreSQL 18，`docker compose up -d` 启动后数据保存在 Docker volume 中，重启后保留消息；`docker compose down -v` 连同数据一起删除。共享入口 `runBackend` 可指定监听地址、端口和数据库连接，默认值与 `compose.yaml` 一致；JVM 与原生服务连接同一个数据库，读取同一份数据。业务逻辑与仓库接口位于 `modeling/src/`，HTTP 路由与 SQL 实现位于 `backend/src/`，sqlx4k 的 PostgreSQL 驱动在公共源码中构造，各平台无需单独适配。

本地开发同时启动后端与 Web：

```sh
# 终端一：先启动数据库；后端也可用 ./kotlin run -m linux 或 -m macos
docker compose up -d
./kotlin run -m jvm

# 终端二
cd web
npm run dev
```

在页面填写用户昵称和房间名后加入房间。同房间的多个浏览器窗口会轮询同步消息；离开后停止拉取。昵称直接作为用户标识，当前版本没有账号登录，房间成员检查不等于身份认证。

Vite 开发服务器与预览服务器把 `/api` 代理到 `http://127.0.0.1:8080`。生产部署需要将 `/api` 转发到后端，使页面与 API 保持同源。

聊天 API 使用共享的 Kotlin Serialization JSON 契约：

| 请求 | 参数/请求体 | 返回 |
| --- | --- | --- |
| `POST /api/chat/join` | `{"userId":"alice","roomId":"lobby"}` | `204`，重复加入无副作用 |
| `POST /api/chat/leave` | 同上 | `204`，消息仍保留 |
| `POST /api/chat/messages` | `{"sender":"alice","roomId":"lobby","content":"你好"}` | `201`，包含数据库 ID 和服务端时间的消息 |
| `GET /api/chat/messages` | `userId`、`roomId` | 当前房间的消息列表 |

业务失败的响应体为 `ChatError` 的 JSON，`type` 字段区分类型。未加入房间的发送/读取返回 `403` 和 `{"type":"NotInRoom"}`；无效参数返回 `400` 和纯文本说明。前端每 2.5 秒读取房间完整历史，省去增量游标与消息合并逻辑。页面除“已不在房间”外只展示成功流程，其他异常提示、重试和补偿由具体业务决定；组件卸载时仍关闭 HTTP 客户端并取消协程。消息最多 4000 字符；用户和房间标识最多 80 字符。

macOS ARM64 原生运行：

```sh
./kotlin build -m macos
./kotlin run -m macos
```

Linux 原生构建与运行（在 Linux 上执行，按机器架构选择 `linuxX64` 或 `linuxArm64`）：

```sh
./kotlin build -m linux -p linuxX64
./kotlin run -m linux -p linuxX64
```

Native 构建需要下载 Kotlin/Native 编译器及平台工具链；macOS 需要安装 Xcode 命令行工具。Linux 二进制的构建与运行应在相应的 Linux 环境验证。

前端需要 Node.js 22.12+（推荐 Node.js 24）和 npm：

```sh
cd web
npm install
npm run dev
```

开发服务器的地址以 Vite 的终端输出为准。生产构建和本地预览：

```sh
npm run build
npm run preview
```

`npm run dev` 和 `npm run build` 都先调用 `./kotlin build -m web -v release`，然后将链接产物复制至忽略提交的 `.kotlin-js/`，再由 Vite 加载、解析 React 依赖并打包。Kotlin Toolchain 0.13 的 JS 链接任务仅在 release 变体中运行；默认 debug 构建只生成 `.klib`，不能直接用于浏览器。生产文件位于 `web/dist/`。

修改 Kotlin 源码后，在另一终端运行 `npm run build:kotlin` 或重启 `npm run dev`；当前骨架没有配置 Kotlin 自动监视编译。

## 验证与工具链边界

```sh
./kotlin test -m backend -p jvm
./kotlin build -m jvm
cd web
npm run build
```

`backend/test@jvm/ChatApiTest.kt` 用 Ktor `testApplication` 验证业务失败经 HTTP 往返后仍是携带同一 `ChatError` 的 `ChatException`，非业务失败保留 Ktor 异常；它注入一个模拟非成员的 `ChatClient`，只验证传输契约，不涉及数据库。`backend/test@jvm/ChatDatabaseTest.kt` 包含一个集成测试，覆盖事务内聊天室读写、重复入群、直接入群失败回滚、入群参与外层事务，以及无事务时使用默认执行器。使用 `embedded-postgres:2.2.2` 和二进制 BOM `18.3.0`，由测试启动、关闭临时 PostgreSQL，不依赖 Docker 或 `compose.yaml`。测试复用实际 repository 的建表语句和 service，只额外添加一个让指定入群消息写入失败的约束，用于验证回滚。版本配置参见 [embedded-postgres 官方说明](https://github.com/zonkyio/embedded-postgres#postgres-version)。

Kotlin Toolchain 0.13 的 `js/app` 仍使用 Vite 补齐浏览器开发与打包。JS 链接任务仅在 release 变体运行，链接目录由 `web/scripts/build-kotlin.mjs` 指定，升级工具链时需检查该脚本。Linux Native 的编译与运行应在 Linux 环境验证。

## 官方资料

- [Kotlin Toolchain](https://kotlin-toolchain.org/latest/)
- [Kotlin/JS 产品与运行方式](https://kotlin-toolchain.org/latest/user-guide/product-types/js-app/)
- [Kotlin React wrappers](https://github.com/JetBrains/kotlin-wrappers)
- [Ktor 服务端引擎与支持平台](https://ktor.io/docs/server-engines.html)
- [Kotlin/Native 应用](https://kotlin-toolchain.org/latest/user-guide/product-types/native-app/)
- [sqlx4k](https://github.com/smyrgeorge/sqlx4k)
- [Ktor JSON 序列化](https://ktor.io/docs/server-serialization.html)
- [Ktor StatusPages](https://ktor.io/docs/server-status-pages.html)
