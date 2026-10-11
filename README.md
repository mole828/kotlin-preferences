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
| `modeling` | Kotlin Multiplatform，JVM + JS + Linux/macOS Native | 领域模型、业务规则与用例、仓库接口，以及共享调用契约和 HTTP 客户端 |

依赖方向为 `web → modeling ← backend ← jvm/linux/macos`。前后端共同依赖 `modeling` 中的业务定义，彼此不直接依赖。`backend` 是 `kmp/lib`，共享源码位于 `backend/src/`；三个应用模块位于 `backend/runtime/`，仅调用共享的 `runBackend()`；后端适配代码和各平台启动入口统一收在 `backend/` 目录下。

### 业务逻辑集中在 modeling

`modeling.ChatService` 通过 `Database.transaction` 执行业务，事务块的接收者是 `TransactionScope`，可以直接访问 `memberships`（`MembershipService`）和 `messages`（`ChatRoomRepository`）：

```kotlin
db.transaction {
    if (memberships.join(userId, roomId)) {
        messages.addMessage(
            ChatMessage(userId, "${userId.value} 加入了群聊", Clock.System.now(), roomId)
        )
    }
}
```

后端 `SqlxDatabase` 开启一次数据库事务，将成员 service 使用的 `SqlxMembershipRepository` 和消息仓库 `SqlxChatRoomRepository` 绑定到同一个事务执行器。正常返回时提交，异常退出或提交前协程已取消时回滚；取消后的回滚在 `NonCancellable` 中执行。入群消息写入失败时，成员关系也会回滚，重试可以完整执行。重复加入不重复发消息。

两个 SQL repository 分别管理成员表和消息表，建表在启动时完成，业务方法只执行查询和写入。发送时的成员检查仍与写入放在同一条 SQL 中。HTTP 调用契约 `ChatClient` 保持不变，业务层不依赖 sqlx4k。

这里使用 Kotlin 的接收者 lambda（`suspend TransactionScope.() -> T`），不需要额外开启 `context` 语言特性。事务块内通过 scope 中的 service 和 repository 组合操作；本版每次 `db.transaction` 都开启新事务，不支持嵌套事务传播。不要在事务块内再次调用开启事务的 `ChatService` 方法。异常需要退出事务块才会触发回滚，捕获异常后正常返回仍会提交。

JVM 测试覆盖入群提交与去重、消息写入失败后成员回滚及重试、协程取消后两份仓库的回滚与连接复用，均使用真实 SQLite。

前端开发者可以直接阅读这些规则和流程，检查界面行为是否符合业务要求，并参与业务变更的审查。业务代码依赖仓库接口，可以注入内存实现独立测试，也为原型演示提供复用空间。

共享源码与实际执行位置分别由模块组织和运行时装配决定。当前 Web 通过 HTTP 调用后端，由后端执行 `ChatService` 中的业务逻辑与校验；前端能够检查或复用规则，服务端仍负责保证请求有效。

### 调用契约与平台适配

聊天的共用入口是 `modeling.ChatClient`。`ChatService` 实现该接口，后端路由只调用这个接口；`modeling.client.KtorChatClient` 也实现同一接口，集中管理 URL、JSON、查询参数和 API 错误。Web 注入 `HttpClient(Js)` 和浏览器同源地址，调用共享客户端并管理界面状态。HttpClient 的创建与关闭由使用它的平台负责。后端使用 Ktor 的 `ContentNegotiation { json() }` 处理 JSON，通过 `StatusPages` 统一映射异常；路由直接调用 `receive<T>()` 和 `respond()`。

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
| `GET /api/chat/messages` | `userId`、`roomId`、可选 ISO 时间 `after` | 当前房间的消息列表 |

未加入房间的发送/读取返回 `403`，无效参数返回 `400`。时间边界包含 `after` 本身，客户端按消息 ID 合并去重，避免同一时间戳的消息丢失。首次读取不传 `after`，获取房间历史。消息最多 4000 字符；用户和房间标识最多 80 字符。

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

共享客户端与后端测试：

```sh
./kotlin test -m modeling -m backend -p jvm
./kotlin test -m backend -p macosArm64
```

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

## 当前工具链边界

Kotlin Toolchain 的 `js/app` 仍处于不完整预览阶段，`./kotlin run -m web` 不支持直接启动浏览器应用，因此项目使用 Vite 补上开发服务器和 Web 打包。链接目录 `build/artifacts/CompiledWebArtifact/webjsrelease/kotlin-output` 是当前工具链的 release 输出约定，升级版本时需检查 `web/scripts/build-kotlin.mjs`。

已验证 npm 安装、Kotlin/JS 编译与链接以及 Vite 生产打包。验证环境限制了 Kotlin CLI 的进程查询和本地端口监听，因此编译验证使用同版本的缓存编译器，浏览器页面尚未实测。正常终端仍使用上述 `npm run dev` 命令启动。

新聊天页已通过缓存 Kotlin/JS 编译、JS 链接与 Vite 打包，产物包含这次实现的共享客户端与页面；验证使用 Kotlin 2.4.10 / Ktor JS 3.5.2，精确声明版本的构建仍需在正常环境运行。浏览器与真实数据库的完整联调尚未验证。

当前聊天模块配置已通过 CLI 识别。共享 JSON 契约和 Ktor Client 的 6 项测试已使用缓存编译器执行通过；后端另有 4 项 API 测试通过，覆盖共享 KtorChatClient 对服务的调用和错误映射，使用内存仓库作为测试替身；验证使用 Kotlin 2.4.10、MockEngine 3.5.2，后端官方插件使用缓存的 Ktor 3.5.2（核心运行库 3.6.0），项目声明的工具链为 Kotlin 2.4.20 / Ktor 3.6.0，因此仍需运行正式构建与测试。仓库中的 SQL 已通过 SQLite 执行检查，覆盖成员检查、房间隔离、包含边界的纳秒时间筛选和稳定消息顺序。

sqlx4k SQLite 驱动尚未缓存，当前环境无法访问依赖仓库，真实数据库集成测试与后端完整构建尚未完成。Native 目标的完整编译、链接与运行也尚未验证。正常开发环境应执行上面的共享客户端和后端测试命令，并验证对应 Native 目标。

## 官方资料

- [Kotlin Toolchain](https://kotlin-toolchain.org/latest/)
- [Kotlin/JS 产品与运行方式](https://kotlin-toolchain.org/latest/user-guide/product-types/js-app/)
- [Kotlin React wrappers](https://github.com/JetBrains/kotlin-wrappers)
- [Ktor 服务端引擎与支持平台](https://ktor.io/docs/server-engines.html)
- [Kotlin/Native 应用](https://kotlin-toolchain.org/latest/user-guide/product-types/native-app/)
- [sqlx4k](https://github.com/smyrgeorge/sqlx4k)
- [Ktor JSON 序列化](https://ktor.io/docs/server-serialization.html)
- [Ktor StatusPages](https://ktor.io/docs/server-status-pages.html)
