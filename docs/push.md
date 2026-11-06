# 消息推送

房间的新消息由服务端主动推送，客户端不再轮询。推送本身不难，难的是三件事：不能推出未提交的数据、不能在历史与实时之间漏掉或重复消息、断线后能续上。下面按这三件事说明设计。

## 契约

```kotlin
fun messages(userId: UserId, roomId: RoomId, after: MessageSeq? = null): Flow<ChatMessage>
```

`ChatClient.messages` 先发出 `after` 之后的历史消息，再持续发出新提交的消息，每条恰好一次、按序号排列。流不会自行结束；用户不在房间（包括收集期间离开）时以 `ChatException(ChatError.NotInRoom)` 失败。`ChatService` 和 `KtorChatClient` 实现同一个 `Flow` 契约，界面和测试不区分本地还是远程。

## 房间内序号

每条消息带有房间内的序号 `MessageSeq`，它同时是消息在房间内的标识和续传游标：断线后用最后收到的序号再次订阅即可。

序号必须按**提交顺序**可见，否则游标会漏消息。自增主键做不到这一点：事务 A 取得 10、事务 B 取得 11，B 先提交，读者看到 11 并记下游标；A 随后提交，10 落在游标之前，再也读不到。时间戳同理，它在提交前就已确定。

`SqlxChatRoomRepository.addMessage` 从房间计数行取号：

```sql
INSERT INTO chat_rooms(room_id, last_seq) VALUES (:roomId, 1)
ON CONFLICT (room_id) DO UPDATE SET last_seq = chat_rooms.last_seq + 1
RETURNING last_seq
```

计数行的锁持有到事务结束，同一房间的写入因此串行，序号顺序就是提交顺序。代价是同一房间的写入不能并发，对聊天室可以接受。取号、写消息和通知在同一个事务中完成：这个事务保证的是存储层的序号不变量，与用例的原子性无关，所以由仓库自己建立（有外层事务时参与外层）。

这条规则只有在两个写事务交错时才会被违反，对照测试的顺序流程测不出来，因此用注释和本文档说明，而不为它编写并发测试。

## 门铃：信号不带数据

`modeling.RoomSignals.changes(roomId)` 只表示“这个房间变了，再看一次”。`ChatService.messages` 对每个信号做同样的事：检查成员关系，再读取最后一条之后的消息。

```kotlin
signals.changes(roomId).collect {
    requireMember(userId, roomId)
    for (message in rooms.messagesAfter(roomId, last)) { emit(message); last = message.seq }
}
```

- **先订阅、后读取**：`changes` 在收集时立即发出一次，读者在订阅之后才读历史，读历史期间到达的变化会再唤醒一次，不会出现历史与实时之间的缝隙。
- **信号可以合并、迟到、重复**：数据始终按游标从表里读，信号丢了只会推迟、不会丢消息；忙碌的读者被十次通知唤醒一次，一次读回十条。
- **离开也是信号**：成员关系变化同样唤醒读者，读者重新检查成员关系，不在房间就以 `NotInRoom` 结束流。

## PostgreSQL 广播与 fanout

- **通知随事务提交**：仓库在写入的同一事务中执行 `SELECT pg_notify('chat_room_changes', room_id)`。PostgreSQL 只在提交后送达通知，回滚时丢弃，所以被回滚的入群消息不会唤醒任何人，业务代码无需关心通知时机。
- **只带房间标识**：`NOTIFY` 的负载上限为 8000 字节，而一条最多 4000 字符的中文消息在 UTF-8 下可达约 12000 字节；只传房间标识，数据由读者自己查询。
- **每个实例一个 LISTEN 连接**：`LISTEN` 独占一个连接，为每个读者建一个会耗尽连接池。`PgRoomSignals` 用一个连接监听，收到通知后在进程内按房间分发。通知来自数据库，多个服务端实例各自监听即可，无需额外的消息中间件；对照测试的 PostgreSQL 环境以两个实例运行，验证了这一点（见 [testing.md](testing.md)）。
- **分发结构**：所有房间的版本号放在一个 `MutableStateFlow<Map<RoomId, Long>>` 中，通知到达时原子地替换整个映射，读者只观察自己房间的版本。不需要锁，也天然合并通知。映射条目不清理，规模以运行期间发生过变化的房间数为上限。

sqlx4k 1.12.0 的 `listen` 有两点需要兜底：

- 它在库自己的作用域中启动监听并立即返回，此时 `LISTEN` 未必生效。`PgRoomSignals.listen` 因此反复发送空负载的探测通知，收到回声后才返回。
- JVM 实现会在连接断开后自动重连，但不报告重连，重连期间的通知会丢失。每个读者因此每 30 秒额外被唤醒一次，丢失的通知最多推迟一条消息 30 秒。

监听与进程同生命周期，无法单独取消。

## HTTP 传输：SSE

数据只从服务端流向客户端，发送仍用 `POST`，因此选择 SSE 而不是 WebSocket：SSE 是普通 HTTP 响应，不需要另行设计双向消息协议。

每个 SSE 事件的数据是共享的 sealed 类型 `MessageStreamEvent`（`Message` 或 `Failure`）的 JSON。流开始后响应头已经发出，业务失败无法再用状态码表达，所以作为最后一个事件传递，客户端还原为 `ChatException`，与非流式接口的失败处理一致（见 [error-contract.md](error-contract.md)）。

续传用显式的 `after` 参数，而不是 SSE 的 `Last-Event-ID` 自动重连：续传位置是契约的一部分，内存、PostgreSQL 和 HTTP 三个环境都能用同一个测试验证。重连由 `ChatSession` 负责（见 [client-session.md](client-session.md)）。

实现中遇到的两个坑，都是由对照测试的 HTTP 环境发现的：

- Ktor 的测试引擎会缓冲完整响应体，永不结束的流到达不了客户端；测试改为在随机端口启动真实的 CIO 服务器。
- 在 Ktor 客户端的 `sse { }` 块中抛出的异常会被包装为 `SSEClientException`；`KtorChatClient` 在块内只记录失败，离开块后再抛出 `ChatException`。
