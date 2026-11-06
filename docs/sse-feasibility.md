# SSE 在 Web 端的可行性验证

对照测试在 JVM 上用真实的 CIO 服务器和 CIO 客户端验证了 SSE 传输。Web 端还有两点没有覆盖：Vite 开发代理是否缓冲 SSE 响应，以及 Ktor 的 JS 引擎能否逐条接收 SSE 事件。为这两点引入浏览器自动化测试环境的成本过高，因此只做了一次性的可行性实验，记录如下。

## 方法

实验不依赖项目代码和数据库，替换掉了两侧不相关的部分：

- **服务端**：一个 Python 脚本监听 `127.0.0.1:8080`（Vite 代理的目标），对任意 GET 返回 `text/event-stream`，每秒发送一个事件，共 5 条消息事件和 1 条失败事件，然后结束响应。事件数据仿照 `MessageStreamEvent` 的 JSON，包含中文。
- **代理**：直接运行 `web` 中的 `vite`，使用项目的 `vite.config` 代理 `/api`。
- **客户端**：一个独立的 Kotlin/JS 小工程，只依赖 Ktor 3.6.0 客户端，用 `HttpClient(Js) { install(SSE) }` 和 `client.sse(url) { incoming.collect { ... } }` 订阅，与 `KtorChatClient.messages` 的用法相同，记录每个事件相对开始时间的到达时刻。在 Node.js 24 上运行。

两种客户端（`curl -N` 和 Ktor JS）分别直连服务端和经过 Vite 代理，共四组。

## 结果

| 客户端 | 直连 | 经 Vite 代理 |
| --- | --- | --- |
| `curl -N` | 每秒一条，逐条到达 | 每秒一条，逐条到达，额外延迟约 20ms |
| Ktor JS 引擎（Node） | 首条 +81ms，之后每隔 1000ms 一条；共 6 个事件，随后识别出流结束 | 首条 +86ms，之后每隔 1000ms 一条；共 6 个事件，随后识别出流结束 |

- Vite 7.3 的开发代理不缓冲 SSE，事件实时转发。
- Ktor 3.6.0 的 JS 引擎支持 SSE 插件，事件逐条到达而不是在响应结束后一次性交付；中文数据完整；服务端结束响应后 `sse { }` 正常返回，`KtorChatClient` 依赖这一点在 `Failure` 事件之后抛出 `ChatException`。

## 未覆盖的部分

- 客户端运行在 Node.js 而不是浏览器中。Ktor 的 JS 引擎在两处都基于 `fetch` 的流式响应体，但浏览器的 `fetch` 实现与 Node 的不同，浏览器中的行为仍以实际打开页面为准。
- 生产环境的反向代理（如 Nginx）默认会缓冲响应，部署时需要为 `/api/chat/messages` 关闭缓冲（Nginx 为 `proxy_buffering off`，或由后端返回 `X-Accel-Buffering: no`）。本实验没有涉及。
- React 界面的渲染没有验证；本次改造只改了消息列表的 `key` 字段。
