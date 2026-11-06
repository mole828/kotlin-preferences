# 测试方式

本项目的测试用来示范业务规则该怎么测，不追求覆盖率。

## 对照测试

业务规则写成一份测试流程 `verifyChatRules(chat: ChatClient)`，只通过 `ChatClient` 接口操作和断言，不直接查询数据库。同一份流程在两个环境中运行：

| 环境 | `ChatService` 的依赖 |
| --- | --- |
| 内存 | `MemoryChatStore` 提供的 repository 与 `Atomicity` |
| PostgreSQL | `SqlxMembershipRepository`、`SqlxChatRoomRepository`、`SqlxAtomicity`，运行在嵌入式 PostgreSQL 上 |

两个环境使用同一个 `ChatService`，业务规则只有一份；替换的只是存储和事务能力。内存实现是逻辑参照：它简单到可以一眼看懂，规则在内存中成立而在 PostgreSQL 上失败，说明 SQL 实现与预期不一致；反过来，内存实现若没有遵守接口契约，例如 `atomic` 失败时不回滚，内存环境会失败。

流程覆盖三条规则：重复加入只发一次入群消息；入群消息写入失败时，加入整体回滚，之后拉取仍得到 `ChatError.NotInRoom`；离开后不能发送和拉取。为制造入群消息写入失败，两个环境各自拒绝同一条内容：内存实现通过构造参数，PostgreSQL 通过测试专用的约束。

## 位置

测试流程、内存实现和两个环境都在 `backend/test@jvm/`：两个环境都能在 backend 中构造，暂时不需要单独的测试模块。出现其他使用方时，例如客户端原型想直接使用内存实现，再将内存实现和测试流程提取到可共享的模块中（Kotlin Toolchain 中一个模块的测试源码不能被其他模块引用）。

嵌入式 PostgreSQL 使用 `embedded-postgres:2.2.2` 和二进制 BOM `18.3.0`，由测试启动和关闭，不依赖 Docker。版本配置参见 [embedded-postgres 官方说明](https://github.com/zonkyio/embedded-postgres#postgres-version)。
