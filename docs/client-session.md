# 客户端会话状态

## 分工

`client` 模块存放只在客户端使用的共享代码：

- `KtorChatClient`：`ChatClient` 的 HTTP 实现，负责 URL、JSON、SSE 消息流和业务失败的还原。
- `ChatSession`：界面无关的聊天流程，负责加入、加入后接收消息、发送、离开、被移出房间，以及操作进行中忽略重复提交。

界面只做两件事：渲染 `ChatSession.state`，以及收集输入（昵称、房间名、消息草稿）后调用会话方法。Web 用一个订阅 `StateFlow` 的小 hook 渲染；以后的 Compose UI 用 `collectAsState()` 即可复用同一套流程。

依赖方向为 `web → client → modeling`。`client` 不放进 `modeling`，因为服务端不需要客户端流程；`KtorChatClient` 随之移出，`modeling` 只保留业务定义和共享契约，不再依赖 Ktor 客户端。

## 状态

```kotlin
data class ChatUiState(val room: RoomState, val busy: Boolean)

sealed interface RoomState {
    data class NotJoined(val removedBy: ChatError?) : RoomState
    data class Joined(val user: UserId, val room: RoomId, val messages: List<ChatMessage>) : RoomState
}
```

消息只存在于 `Joined` 中，不会出现“有用户、没房间”或“未加入却有消息”的组合。`NotJoined.removedBy` 记录被移出房间的业务原因；提示文字由界面根据原因决定，例如 Web 对 `ChatError.NotInRoom` 显示“你已不在该房间，请重新加入”。主动离开时原因为空。

## 行为约定

- 方法都是 `suspend`，由界面在自己的作用域中调用；接收消息运行在构造时传入的作用域中，取消该作用域即结束会话。应在界面的主调度器上调用。
- 操作进行中（`busy`）再次调用任何操作会被直接忽略，由 `Mutex.tryLock` 保证。
- `send` 返回是否发送成功，界面据此决定是否清空草稿。发出的消息和其他人的消息一样经消息流到达，不单独刷新。
- 消息流或发送收到 `ChatError.NotInRoom` 时，会话回到 `NotJoined(removedBy = NotInRoom)` 并停止接收。
- 消息流因网络等原因中断时，等待 `reconnectDelay` 后用最后收到的序号续传，不重复也不遗漏（见 [push.md](push.md)）。
- 界面操作中业务失败以外的异常（网络错误等）照常抛给调用方，与直接调用 `ChatClient` 一致。
- 离开后才到达的消息会被丢弃，不会让会话回到已加入状态。
