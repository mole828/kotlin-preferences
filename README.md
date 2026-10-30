# Kotlin Preferences

用于记录和实践项目技术倾向的 Kotlin 多模块项目。使用 Kotlin Toolchain 0.13.0（原 Amper），构建版本及校验和固定在根目录的 `kotlin` / `kotlin.bat` 中。

聊天功能作为示例，用来展示业务逻辑、界面、传输和存储各自放在哪里。项目重点是将业务模型、规则与用例集中在 `modeling`，让前后端开发者都能直接理解、审查和测试同一份业务逻辑。

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

`modeling.ChatService` 注入 `MembershipService` 和 `ChatRoomRepository`，负责业务校验、入群消息和消息存取。service 与 repository 在启动时创建并长期复用。业务模块不声明数据库或事务抽象。

后端在请求参数解析完成后，用 sqlx4k 的 `TransactionContext.withCurrent(database)` 包住业务调用，事务成功结束后再发送 HTTP 响应：

```kotlin
val request = call.receive<RoomMembershipRequest>()
TransactionContext.withCurrent(database) {
    chatClient.join(request.userId, request.roomId)
}
call.respond(HttpStatusCode.NoContent)
```

两个 SQL repository 在构造时接收默认 `QueryExecutor`，通过 backend 的扩展函数 `database.currentExecutor()` 获取执行器：有事务上下文时使用当前事务，没有时使用默认 executor。SQL 失败直接向上抛出，不切换执行器重试。HTTP 调用保留事务边界；事务外也可以调用 service，但多条 SQL 不保证整体提交或回滚。

成员表与消息表由各自 repository 管理，启动时建表。事务内入群消息写入失败时，成员关系一起回滚；重复加入不重复发消息。发送时的成员检查与写入保持在同一条 SQL 中。

当前按单数据库使用，事务生命周期沿用 sqlx4k 1.12.0：`withCurrent` 复用已有事务，不添加自定义 key 或事务传播规则。详见 [sqlx4k TransactionContext](https://github.com/smyrgeorge/sqlx4k#transactioncontext-coroutines)。

前端开发者可以直接阅读这些规则和流程，检查界面行为是否符合业务要求，并参与业务变更的审查。业务代码依赖仓库接口，可以注入内存实现独立测试，也为原型演示提供复用空间。

共享源码与实际执行位置分别由模块组织和运行时装配决定。当前 Web 通过 HTTP 调用后端，由后端执行 `ChatService` 中的业务逻辑与校验；前端能够检查或复用规则，服务端仍负责保证请求有效。

### 调用契约与平台适配

聊天的共用入口是 `modeling.ChatClient`。`ChatService` 实现该接口，后端路由只调用这个接口；`modeling.client.KtorChatClient` 也实现同一接口，集中管理 URL、JSON 和查询参数，HTTP 错误交给 Ktor 默认处理。Web 注入 `HttpClient(Js)` 和浏览器同源地址，调用共享客户端并管理界面状态。HttpClient 的创建与关闭由使用它的平台负责。后端使用 Ktor 的 `ContentNegotiation { json() }` 处理 JSON，通过 `StatusPages` 统一映射异常；路由直接调用 `receive<T>()` 和 `respond()`。

Kotlin 源码统一由 Kotlin Toolchain 管理，通过 `project.yaml` 聚合各模块，各模块使用 `module.yaml` 声明产品类型和依赖。前端 React Kotlin 包装器通过 Maven BOM 统一版本；React 的 JS 运行时与 Vite 在 `web/package.json` 中声明。

## 构建与运行

macOS / Linux 使用 `./kotlin`，Windows 使用 `kotlin.bat`。包装脚本首次执行时会自动下载工具链和 Java 运行时，需要访问 JetBrains 和 Maven 仓库。

```sh
./kotlin show modules
./kotlin build
./kotlin run -m jvm
```

后端使用 Ktor 3.6.0 和 CIO 引擎，默认监听 `127.0.0.1:8080`。`GET /` 返回项目技术信息，`GET /health` 返回 `OK`。聊天记录与房间成员通过 sqlx4k 1.12.0 存入 SQLite；默认数据库文件为启动工作目录下的 `chat.db`，重启后保留消息。共享入口 `runBackend(host, port)` 可指定监听地址和端口。不同平台若要读取同一份数据，应使用同一个数据库文件路径。业务逻辑与仓库接口位于 `modeling/src/`，HTTP 路由与 SQL 实现位于 `backend/src/`；`backend/src@jvm/` 与 `backend/src@native/` 仅负责构造对应平台的 SQLite 驱动。

本地开发同时启动后端与 Web：

```sh
# 终端一：也可用 ./kotlin run -m macos
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

未加入房间的发送/读取返回 `403`，无效参数返回 `400`。前端每 2.5 秒读取房间完整历史，省去增量游标与消息合并逻辑。页面只展示成功流程，异常提示、重试和补偿由具体业务决定；组件卸载时仍关闭 HTTP 客户端并取消协程。消息最多 4000 字符；用户和房间标识最多 80 字符。

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

`backend/test@jvm/ChatDatabaseTest.kt` 包含一个集成测试，覆盖事务内聊天室读写、重复入群、入群失败回滚，以及无事务时使用默认执行器。使用 `embedded-postgres:2.2.2` 和二进制 BOM `18.3.0`，由测试启动、关闭临时 PostgreSQL，无需手动安装数据库。运行时仍使用 SQLite，因此测试仅为消息表提供 PostgreSQL 的自增列建表语法，复用实际 repository 和 service。版本配置参见 [embedded-postgres 官方说明](https://github.com/zonkyio/embedded-postgres#postgres-version)。

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
