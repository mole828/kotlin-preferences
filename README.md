# Kotlin Preferences

用于记录和实践项目技术倾向的 Kotlin 多模块项目。使用 Kotlin Toolchain 0.13.0（原 Amper），构建版本及校验和固定在根目录的 `kotlin` / `kotlin.bat` 中。

## 模块与技术倾向

| 模块 | 技术 | 职责 |
| --- | --- | --- |
| `web` | Kotlin/JS、kotlin-react、Vite | 聊天界面：加入/离开房间、发送消息、历史记录与轮询更新 |
| `backend` | Ktor CIO、Kotlin Multiplatform | JVM、Linux Native、macOS Native 共享的 Ktor 聊天服务与 sqlx4k 持久化 |
| `jvm` | Kotlin/JVM | JVM 服务启动入口 |
| `linux` | Kotlin/Native，Linux x64 + ARM64 | Linux 原生服务启动入口 |
| `macos` | Kotlin/Native，macOS ARM64 | 本地开发用的 macOS 原生服务启动入口 |
| `modeling` | Kotlin Multiplatform，JVM + JS + Linux/macOS Native | 前后端共享的领域模型、ChatClient 接口与 Ktor Client 实现 |

依赖方向为 `web → modeling ← backend ← jvm/linux/macos`。共享模块集中声明聊天调用契约；前后端不直接依赖彼此。`backend` 是 `kmp/lib`，共享源码位于 `backend/src/`；三个应用模块位于 `backend/runtime/`，仅调用共享的 `runBackend()`；公共服务代码和各平台启动入口统一收在 `backend/` 目录下。

聊天的共用入口是 `modeling.ChatClient`。`ChatService` 实现该接口并委派给 `ChatRoomRepository`，后端路由只调用这个接口；`modeling.client.KtorChatClient` 也实现同一接口，集中管理 URL、JSON、查询参数和 API 错误。Web 不实现 HTTP 请求协议，只注入 `HttpClient(Js)` 和浏览器同源地址，调用共享客户端并管理界面状态。HttpClient 的创建与关闭由使用它的平台负责。 后端使用 Ktor 的 `ContentNegotiation { json() }` 处理 JSON，通过 `StatusPages` 统一映射异常；路由直接调用 `receive<T>()` 和 `respond()`。

Kotlin 源码统一由 Kotlin Toolchain 管理，通过 `project.yaml` 聚合各模块，各模块使用 `module.yaml` 声明产品类型和依赖。前端 React Kotlin 包装器通过 Maven BOM 统一版本；React 的 JS 运行时与 Vite 在 `web/package.json` 中声明。

## 构建与运行

macOS / Linux 使用 `./kotlin`，Windows 使用 `kotlin.bat`。包装脚本首次执行时会自动下载工具链和 Java 运行时，需要访问 JetBrains 和 Maven 仓库。

```sh
./kotlin show modules
./kotlin build
./kotlin run -m jvm
```

后端使用 Ktor 3.6.0 和 CIO 引擎，默认监听 `127.0.0.1:8080`。`GET /` 返回项目技术信息，`GET /health` 返回 `OK`。聊天记录与房间成员通过 sqlx4k 1.12.0 存入 SQLite；默认数据库文件为启动工作目录下的 `chat.db`，重启后保留消息。共享入口 `runBackend(host, port)` 可指定监听地址和端口。不同平台若要读取同一份数据，应使用同一个数据库文件路径。服务逻辑、SQL 和接口都在公共源码中；`src@jvm/` 与 `src@native/` 仅负责构造对应平台的 SQLite 驱动。

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
