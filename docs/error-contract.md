# 调用契约与业务失败

## 共享的调用契约

聊天的共用入口是 `modeling.ChatClient`。`ChatService` 实现该接口，后端路由只调用这个接口；`client` 模块中的 `KtorChatClient` 也实现同一接口，集中管理 URL、JSON 和查询参数。Web 注入 `HttpClient(Js)` 和浏览器同源地址，通过 `ChatSession` 调用共享客户端（见 [client-session.md](client-session.md)）。HttpClient 的创建与关闭由使用它的平台负责。后端使用 Ktor 的 `ContentNegotiation { json() }` 处理 JSON，通过 `StatusPages` 统一映射异常；路由直接调用 `receive<T>()` 和 `respond()`。

## 业务失败属于契约

业务失败由可序列化的 sealed 类型 `ChatError` 表示，例如 `ChatError.NotInRoom`。两个实现都抛出携带它的 `ChatException(error)`，调用方不需要知道背后是本地 service 还是 HTTP，用 `when (e.error)` 处理。

异常只负责中断流程，HTTP 只传输其中的 `ChatError`：

- 后端按 `statusOf(error)` 选择状态码，以 `ChatError` 为声明类型返回 `{"type":"NotInRoom"}`。响应按传入值的静态类型序列化：静态类型是 `ChatError.NotInRoom` 这样的具体类型时，响应体缺少 `type` 字段，客户端无法还原。因此显式写 `respond<ChatError>`，不依赖类型推断；这个坑由对照测试的 HTTP 环境守住。
- `KtorChatClient` 对所有非 2xx 响应尝试把响应体解码为 `ChatError`，成功则重新包装成 `ChatException`，不依赖状态码或提示文字；因此无论 `statusOf` 为某类失败选择 4xx 还是 5xx，都会还原为 `ChatException`。
- 消息流的响应在失败前已经开始，业务失败作为最后一个 SSE 事件 `MessageStreamEvent.Failure` 传输，客户端同样还原为 `ChatException`（见 [push.md](push.md)）。
- 其他 HTTP 失败（参数无效、路由不存在、服务器错误）不属于业务结果，后端返回纯文本，客户端保留 Ktor 的 `ClientRequestException` / `ServerResponseException`。

新增业务失败时，在 `ChatError` 中增加一个子类型；编译器会要求 `statusOf` 和各处 `when` 处理它，`KtorChatClient` 无需修改。

`ChatSession` 据此处理一种业务情况：同一昵称在另一个页面离开房间后，本页的消息流或发送收到 `ChatError.NotInRoom`，会话回到未加入状态并记录原因，界面据此回到加入表单并提示重新加入。

## 为什么用异常传递

Kotlin 的设计建议通常是：可预期的失败用针对具体业务的 sealed 返回类型表达，异常留给程序错误。本项目选择异常有两个具体原因：`atomicity.atomic` 依靠异常触发回滚，若失败作为返回值，事务中的业务失败不会自动回滚，每个用例都要自行判断；用异常时，`ChatService` 的业务步骤是一条直线（检查成员、写入消息、返回结果），不必在每一步之后处理“失败则提前返回”。失败的种类仍由 `modeling` 中的 sealed 类型定义，异常只是传递方式。这是基于本项目事务机制的取舍，不是 Kotlin 的通用做法。

以返回值传递的替代方案（Rich Errors、kotlinx-rpc）的实验见 [rich-errors-and-krpc.md](rich-errors-and-krpc.md)。

## 失败的分层

`ChatError` 分层表达失败：根接口圈定捕获范围，即 `ChatClient` 契约内的全部业务失败；中间层按失败的性质分类，目前只有 `ChatError.NotPermitted`（无权执行该操作），`NotInRoom` 属于这一类。

中间层的名称明确说明发生了什么，让调用方据此判断如何处理，不使用 HTTP 概念；backend 的 `statusOf` 只按类别选择状态码（`NotPermitted` 对应 `403`），同一类别下新增具体失败时无需修改映射。调用方可以按类别处理，也可以像 `ChatSession` 那样针对具体失败处理。

出现其他性质的失败时再增加对应类别，例如“目标不存在”“与当前状态冲突”；若出现只关心某一业务区域（如成员关系）的处理方，再增加按区域划分的父接口，sealed interface 允许一个失败同时属于某个性质类别和某个业务区域。类别根据实际出现的失败划分，不预先设立。
