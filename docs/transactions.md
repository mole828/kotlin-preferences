# 业务逻辑与事务边界

## 业务逻辑集中在 modeling

`modeling.ChatService` 注入 `MembershipRepository`、`ChatRoomRepository` 和 `Atomicity`，负责成员检查、入群消息和消息存取。`UserId` 与 `RoomId` 在构造时校验 1–80 字符，`MessageContent` 在构造时校验非空且最多 4000 字符，非法值无法进入业务代码。仓库只负责存取，不承载业务规则：只有房间成员能发送和拉取消息，这条规则由 `ChatService` 检查。service 与 repository 在启动时创建并长期复用。业务模块不依赖具体数据库或 sqlx4k；`Atomicity.atomic` 提供用例需要的原子执行能力，构造 service 时注入一次。

前端开发者可以直接阅读这些规则和流程，检查界面行为是否符合业务要求，并参与业务变更的审查。业务代码依赖仓库接口，测试时注入实际 repository，在嵌入式数据库上验证业务规则；仓库接口也为原型演示提供替换空间。

共享源码与实际执行位置分别由模块组织和运行时装配决定。当前 Web 通过 HTTP 调用后端，由后端执行 `ChatService` 中的业务逻辑与校验；前端能够检查或复用规则，服务端仍负责保证请求有效。

## 事务边界由用例决定

事务边界由用例的原子性要求决定，统一通过 `Atomicity` 声明在 `modeling` 中。后端路由不建立事务，只解析参数、调用业务并返回响应：

```kotlin
val request = call.receive<RoomMembershipRequest>()
chatClient.join(request.userId, request.roomId)
call.respond(HttpStatusCode.NoContent)
```

`ChatService.join` 在同一个 `atomicity.atomic` 中完成加入房间和写入入群消息，即使调用方没有建立事务也不会降级为两次独立提交；有外层事务时参与该事务，不提前提交。入群消息写入失败时，成员关系一起回滚；重复加入不重复发消息。其他操作不需要跨语句原子性，直接使用默认执行器。发送和拉取先检查成员关系再读写消息，两步之间不加事务：用户恰好在两步之间离开，等同于离开前完成了这次操作。若离开房间将来带有清理等副作用，需要重新评估这一点。新增需要多步写入的用例时，在 service 中用 `atomicity.atomic` 包住这些步骤。

临时组合多个业务调用、又需要原子性时（例如尚未沉淀为用例的换房间），路由可以使用与 `ChatService` 相同的 `Atomicity` 实例，而不是直接调用 sqlx4k：

```kotlin
val request = call.receive<SwitchRoomRequest>()
atomicity.atomic {
    chatClient.leave(request.userId, request.fromRoomId)
    chatClient.join(request.userId, request.toRoomId)
}
call.respond(HttpStatusCode.NoContent)
```

`join` 内部的 `atomic` 会参与这个外层事务，任一步失败时整体回滚。`call.respond` 放在 `atomic` 之外：事务提交成功后才响应，也避免网络写出期间占用事务。这种组合一旦成为稳定的业务规则，应移入 `ChatService`，路由恢复为单次业务调用。

## 执行器与 sqlx4k

两个 SQL repository 接收默认 `QueryExecutor`，通过 backend 的扩展函数 `database.currentExecutor()` 获取执行器：有事务上下文时使用当前事务，没有时使用默认数据库执行器。SQL 失败直接向上抛出，不切换执行器重试。backend 的 `SqlxAtomicity` 接收同一数据库的 `Driver`，通过 sqlx4k 的 `TransactionContext.withCurrent` 复用或建立事务，仓库接口不负责管理事务。

每个 repository 只访问自己的表，不写跨表 SQL；跨 repository 的一致性要求只通过 `atomicity.atomic` 声明。成员表与消息表由各自 repository 管理，启动时建表。`SqlxAtomicity` 依赖单库事务，若将来拆分存储，这些调用点就是需要重新设计一致性方案的位置。

当前按单数据库使用，事务生命周期沿用 sqlx4k 1.12.0：`withCurrent` 复用已有事务，不添加自定义 key 或事务传播规则。详见 [sqlx4k TransactionContext](https://github.com/smyrgeorge/sqlx4k#transactioncontext-coroutines)。

## 测试

事务边界中“入群消息写入失败时成员关系回滚”这条规则，由对照测试在内存实现、PostgreSQL 和 HTTP 上分别验证，见 [testing.md](testing.md)。
