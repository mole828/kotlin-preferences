# 登录与身份

## 身份由服务端认定

此前用户标识由客户端在请求体或查询参数中自报，任何人都能以别人的名义发送和读取消息。现在采用用户名加密码登录：注册或登录成功后，服务端签发 JWT，之后每个聊天请求以 `Authorization: Bearer` 携带它，服务端从令牌中取出用户，请求体不再包含“我是谁”。

用户名就是显示名，注册时检查唯一，之后不可修改。因此消息、成员关系的数据模型无需改变，也不存在“改名后旧消息显示什么”“昵称重复”的问题。

## 契约：先登录，才有聊天能力

```kotlin
interface ChatGateway {
    suspend fun register(username: UserId, password: Password): ChatClient
    suspend fun login(username: UserId, password: Password): ChatClient
}

interface ChatClient {
    val user: UserId
    suspend fun join(roomId: RoomId)
    suspend fun send(roomId: RoomId, content: MessageContent): ChatMessage
    fun messages(roomId: RoomId, after: MessageSeq? = null): Flow<ChatMessage>
    ...
}
```

共享契约描述的是“已登录用户能做什么”：`ChatClient` 只能从 `ChatGateway` 取得，方法不再带 `userId`。“先登录才能聊天”由类型保证，而不是靠约定。

业务规则仍显式接收身份，符合“决定业务行为的数据显式传入”：`ChatService.send(sender, roomId, content)`。两者之间只有一层转发 `ChatService.actingAs(user): ChatClient`，在调用方已确认身份之后固定用户。

| | 本地（内存、PostgreSQL） | HTTP |
| --- | --- | --- |
| `ChatGateway` | `LocalChatGateway`：`AccountService` + `actingAs` | `KtorChatGateway`：调用 `/api/auth/*`，取得令牌 |
| `ChatClient` | `chatService.actingAs(user)` | 携带令牌的 HTTP 客户端 |
| 身份来源 | 网关登录得到的 `UserId` | 路由插件校验令牌，取出 `UserId` 后调用 `ChatService` |

`actingAs` 让本地实现与 HTTP 实现可以在测试和界面中互换，这是对照测试能同时覆盖 HTTP 的前提。JWT 只出现在 `backend` 和 `client` 两个边界上，`modeling` 不知道它的存在。

## 账户规则

`AccountService` 位于 `modeling`：注册时用户名已存在则失败（`ChatError.UsernameTaken`）；登录时用户不存在与密码错误以同一个 `ChatError.InvalidCredentials` 失败，调用方无法借此探测哪些用户名已注册。`Password` 值类重写了 `toString()`，密码不会出现在日志或异常信息中。

## 密码哈希：PBKDF2-HMAC-SHA256

后端需要同时运行在 JVM 和 Kotlin/Native 上，不能使用 JVM 的加密库。`Pbkdf2PasswordHasher` 用多平台的纯 Kotlin HMAC 库（kotlincrypto）实现 PBKDF2，默认迭代 60 万次（OWASP 对 PBKDF2-HMAC-SHA256 的建议）。哈希值记录算法、迭代次数和盐，提高迭代次数只影响新密码，旧哈希仍能校验。

没有选择 PostgreSQL 的 pgcrypto（bcrypt）：那需要把明文密码作为 SQL 参数发给数据库，开启语句日志时会被记录。

## 令牌：HS256 JWT

Ktor 的 JWT 插件只支持 JVM，`JwtTokens` 用同一个 HMAC 库实现了服务端唯一用到的 HS256：

- 载荷只有 `sub`（用户名）和 `exp`，有效期 1 天，不做续期；过期后重新登录即可，这是账号密码制相比匿名身份的简化之处。
- 只接受与签发时完全相同的头部，排除 `"alg": "none"` 等算法替换；签名比较与时间无关。
- 令牌在过期前无法吊销，有效期限定了泄露令牌的可用时间。
- 签名密钥来自环境变量 `CHAT_JWT_SECRET`，由各平台的启动入口读取后传给 `runBackend`；未配置时使用源码中的开发密钥并打印警告。多实例部署必须共用同一个密钥。

请求认证不查数据库，只验证签名和有效期。

## 认证失败的传递

聊天路由由一个路由级插件认证：缺少、无效或过期的令牌以 `ChatError.SessionExpired` 失败。认证在路由处理之前进行，因此即使是 SSE 消息流，也能在响应开始前返回 `401`，响应体同样是 `ChatError`。客户端沿用现有的还原逻辑；SSE 被拒绝时 Ktor 抛出 `SSEClientException`，`KtorChatClient` 从其响应中还原 `ChatException`。

`SessionExpired` 只有远程实现会产生，本地实现中身份在取得 `ChatClient` 时已经确定。对照测试的共享流程因此不覆盖它；令牌过期时普通请求和 SSE 都还原为 `SessionExpired`，是用签发已过期令牌的服务端一次性验证的。`ChatSession` 收到它时回到未加入状态，Web 界面据此回到登录表单。

## 未覆盖的部分

- 没有限制登录尝试频率，密码可被在线暴力猜测。
- Native 后端上 60 万次迭代的耗时没有测量；对照测试使用 1000 次迭代以保持速度。
- 没有修改密码、注销账户等功能。
