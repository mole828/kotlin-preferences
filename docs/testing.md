# 测试方式

本项目的测试用来示范业务规则该怎么测，不追求覆盖率。

## 对照测试

业务规则写成一份测试流程 `verifyChatRules(chat: ChatClient)`，只通过 `ChatClient` 接口操作和断言，不直接查询数据库。消息流在后台收集，测试一边操作一边读取下一条消息。同一份流程在三个环境中运行：

| 环境 | `ChatService` 的依赖 |
| --- | --- |
| 内存 | `MemoryChatStore` 提供的 repository、`RoomSignals` 与 `Atomicity` |
| PostgreSQL | `SqlxMembershipRepository`、`SqlxChatRoomRepository`、`PgRoomSignals`、`SqlxAtomicity`；两个实例各用独立的连接池和 LISTEN 连接，共用一个嵌入式 PostgreSQL |
| HTTP | 服务端用内存实现构造 `ChatService`，在随机端口启动真实的 CIO 服务器；测试流程拿到的是 `KtorChatClient` |

三个环境使用同一个 `ChatService`，业务规则只有一份；替换的只是存储和事务能力。内存实现是逻辑参照：它简单到可以一眼看懂，规则在内存中成立而在 PostgreSQL 上失败，说明 SQL 实现与预期不一致；反过来，内存实现若没有遵守接口契约，例如 `atomic` 失败时不回滚，内存环境会失败。内存实现的信号也模仿 `NOTIFY`：`atomic` 中的变化在最外层成功结束后才发出。

PostgreSQL 环境模拟多实例部署：写操作经第一个实例，消息流从第二个实例读取（`verifyChatRules(chat, reader)`，其他环境两者相同）。第二个实例上从未发生写入，读者只能被 PostgreSQL 的通知唤醒，因此规则通过即说明跨实例广播成立。单实例读写走的是同一条通知路径，已被包含在内。

HTTP 环境验证的是传输：业务失败穿过 HTTP 后，客户端仍应得到同一个 `ChatException(ChatError.NotInRoom)`，与直接调用 `ChatService` 没有区别。这正是类型安全全栈要兑现的承诺，测试流程不需要为远程调用改动一行。服务端接内存实现而不是 PostgreSQL，是为了一个环境只引入一个变量：数据库差异已由 PostgreSQL 环境对照，HTTP 环境失败只可能是传输问题。例如服务端用具体类型而不是 `ChatError` 响应错误时，响应体缺少 `type` 字段，只有 HTTP 环境会失败（见 [error-contract.md](error-contract.md)）。

流程覆盖四条规则：重复加入只发一次入群消息，读者先收到历史、再收到新消息；入群消息写入失败时，加入整体回滚，被回滚的入群消息不会出现在任何读者的流中；读者可以从最后收到的序号续传；离开会结束该用户的消息流，之后不能发送和读取。为制造入群消息写入失败，各环境拒绝同一条内容：内存实现通过构造参数，PostgreSQL 通过测试专用的约束。

## 位置

测试流程、内存实现和三个环境都在 `backend/test@jvm/`：三个环境都能在 backend 中构造，暂时不需要单独的测试模块。出现其他使用方时，例如客户端原型想直接使用内存实现，再将内存实现和测试流程提取到可共享的模块中（Kotlin Toolchain 中一个模块的测试源码不能被其他模块引用）。

嵌入式 PostgreSQL 使用 `embedded-postgres:2.2.2` 和二进制 BOM `18.3.0`，由测试启动和关闭，不依赖 Docker。版本配置参见 [embedded-postgres 官方说明](https://github.com/zonkyio/embedded-postgres#postgres-version)。
