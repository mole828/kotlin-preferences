# Kotlin Preferences

用于记录和实践项目技术倾向的 Kotlin 多模块项目。使用 Kotlin Toolchain 0.13.0（原 Amper），构建版本及校验和固定在根目录的 `kotlin` / `kotlin.bat` 中。

聊天功能作为示例，用来展示业务逻辑、界面、传输和存储各自放在哪里。项目重点是将业务模型、规则与用例集中在 `modeling`，让前后端开发者都能直接理解、审查和测试同一份业务逻辑。

## 前提：全栈一体

这套结构基于一个前提：整个项目，从业务模型、前后端到部署，由一个人（借助 AI）负责。

按康威定律，系统结构往往映射组织的沟通结构：前后端分属不同团队、各自发布时，双方需要一份语言无关的接口文档，并为对方的旧版本保留兼容，契约只能靠沟通和约定维持。当一个人负责全部环节时，这些协调成本不再存在，真正的风险变成“改了一处，忘了另一处”。

因此本项目让前后端共同依赖 `modeling` 中的同一份 Kotlin 定义：请求、响应、业务失败都是共享类型，修改业务定义后，编译器会指出前端、后端和传输层所有受影响的位置。类型安全的全栈 Kotlin 是这一结构的基础，而不是可替换的技术选择。反过来，这里不为非 Kotlin 客户端或前后端独立发版做额外设计；若项目变为多团队分别负责，需要重新评估这些取舍。

## 模块

| 模块 | 技术 | 职责 |
| --- | --- | --- |
| `modeling` | Kotlin Multiplatform，JVM + JS + Linux/macOS Native | 领域模型、业务规则与用例、仓库接口，以及共享 HTTP 契约 |
| `client` | Kotlin Multiplatform，JVM + JS | 客户端共享代码：HTTP 客户端 `KtorChatClient` 与界面无关的会话状态 `ChatSession` |
| `backend` | Ktor CIO、Kotlin Multiplatform | 三个服务端平台共享的 HTTP 路由、业务服务装配与 sqlx4k 持久化 |
| `jvm` / `linux` / `macos` | Kotlin/JVM、Kotlin/Native | 服务启动入口，位于 `backend/runtime/`，仅调用共享的 `runBackend()` |
| `web` | Kotlin/JS、kotlin-react、Vite | 聊天界面，只负责渲染 `ChatSession` 的状态和收集输入 |

依赖方向为 `web → client → modeling ← backend ← jvm/linux/macos`，前后端彼此不直接依赖。`client` 只被客户端使用，`modeling` 因此不依赖 Ktor 客户端。

## 设计要点

- **业务集中在 `modeling`**：`ChatService` 负责成员检查、入群消息和消息存取；值类在构造时校验，非法值进不了业务代码；仓库只负责存取。
- **事务边界由用例决定**：用例通过 `Atomicity.atomic` 声明原子性，路由不建立事务；sqlx4k 的事务上下文只出现在 backend。详见 [docs/transactions.md](docs/transactions.md)。
- **共享调用契约**：`ChatService` 与 `KtorChatClient` 实现同一个 `ChatClient`。业务失败是可序列化的 sealed `ChatError`，两侧都以 `ChatException(error)` 抛出，HTTP 只传输 `ChatError`。详见 [docs/error-contract.md](docs/error-contract.md)。
- **消息推送**：`ChatClient.messages` 返回 `Flow`，服务端经 SSE 推送；消息带房间内按提交顺序可见的序号，PostgreSQL 的 `NOTIFY` 随事务提交，每个实例一个 `LISTEN` 连接在进程内分发。详见 [docs/push.md](docs/push.md)。
- **界面状态逻辑共享**：加入、接收消息、发送、离开、被移出房间和防重复提交由 `client` 中的 `ChatSession` 处理，以 `StateFlow<ChatUiState>` 暴露状态；界面只负责渲染，以后的 Compose UI 可直接复用。详见 [docs/client-session.md](docs/client-session.md)。

## 构建与运行

macOS / Linux 使用 `./kotlin`，Windows 使用 `kotlin.bat`。包装脚本首次执行时会自动下载工具链和 Java 运行时。前端需要 Node.js 22.12+（推荐 24）。

```sh
# 终端一：PostgreSQL 与后端（也可用 -m linux -p linuxX64 或 -m macos）
docker compose up -d
./kotlin run -m jvm

# 终端二：Web 开发服务器
cd web
npm install
npm run dev
```

后端默认监听 `127.0.0.1:8080`，`GET /health` 返回 `OK`。消息表结构随推送改为房间内序号，旧的本地数据库需先执行 `docker compose down -v`。`compose.yaml` 提供本地 PostgreSQL 18，数据保存在 Docker volume 中，`docker compose down -v` 连同数据一起删除；`runBackend` 的数据库默认值与它一致。Vite 把 `/api` 代理到后端，生产部署同样需要将 `/api` 转发到后端，保持同源。

修改 Kotlin 源码后运行 `npm run build:kotlin` 或重启 `npm run dev`，当前没有自动监视编译。`npm run build` 生成 `web/dist/`。

页面填写昵称和房间名后加入；同房间的多个窗口实时收到新消息。昵称直接作为用户标识，没有账号登录，成员检查不等于身份认证。

## 聊天 API

| 请求 | 参数/请求体 | 返回 |
| --- | --- | --- |
| `POST /api/chat/join` | `{"userId":"alice","roomId":"lobby"}` | `204`，重复加入无副作用 |
| `POST /api/chat/leave` | 同上 | `204`，消息仍保留 |
| `POST /api/chat/messages` | `{"sender":"alice","roomId":"lobby","content":"你好"}` | `201`，包含房间内序号和服务端时间的消息 |
| `GET /api/chat/messages` | `userId`、`roomId`，可选 `after` | SSE 流：`after` 之后的消息，然后是新消息 |

未加入房间时返回 `403` 和 `{"type":"NotInRoom"}`，消息流则以一个 `Failure` 事件结束；无效参数返回 `400` 和纯文本说明。消息最多 4000 字符，用户和房间标识最多 80 字符。

## 验证

```sh
./kotlin test -m backend -p jvm
./kotlin build -m jvm
cd web && npm run build
```

`ChatRulesTest` 是一个对照测试：同一份业务规则流程分别在内存实现、嵌入式 PostgreSQL 和 HTTP 上运行，用来发现 SQL 实现或传输与逻辑参照之间的差异，详见 [docs/testing.md](docs/testing.md)。Linux Native 的编译与运行应在 Linux 环境验证；macOS Native 需要 Xcode 命令行工具。

## 工具链说明

- Kotlin Toolchain 0.13 的 JS 链接任务只在 release 变体运行，默认 debug 构建只生成 `.klib`。`npm run dev` / `build` 先调用 `./kotlin build -m web -v release`，再把产物复制到 `.kotlin-js/` 交给 Vite；链接目录由 `web/scripts/build-kotlin.mjs` 指定，升级工具链时需检查该脚本。
- 前端 React 包装器通过 Maven BOM 统一版本；React 运行时与 Vite 在 `web/package.json` 中声明。

## 文档

- [docs/transactions.md](docs/transactions.md)：业务逻辑、事务边界与 sqlx4k 执行器
- [docs/error-contract.md](docs/error-contract.md)：调用契约、业务失败的传递与分层
- [docs/push.md](docs/push.md)：消息推送、房间内序号与 PostgreSQL 广播
- [docs/client-session.md](docs/client-session.md)：客户端会话状态与界面的分工
- [docs/testing.md](docs/testing.md)：对照测试的做法与位置
- [docs/rich-errors-and-krpc.md](docs/rich-errors-and-krpc.md)：Rich Errors 与 kotlinx-rpc 实验记录

## 官方资料

- [Kotlin Toolchain](https://kotlin-toolchain.org/latest/)、[Kotlin/JS 应用](https://kotlin-toolchain.org/latest/user-guide/product-types/js-app/)、[Kotlin/Native 应用](https://kotlin-toolchain.org/latest/user-guide/product-types/native-app/)
- [Kotlin React wrappers](https://github.com/JetBrains/kotlin-wrappers)
- [Ktor 服务端引擎](https://ktor.io/docs/server-engines.html)、[Ktor JSON 序列化](https://ktor.io/docs/server-serialization.html)、[Ktor StatusPages](https://ktor.io/docs/server-status-pages.html)
- [sqlx4k](https://github.com/smyrgeorge/sqlx4k)
