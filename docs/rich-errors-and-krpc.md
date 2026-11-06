# 实验：Rich Errors 与 kotlinx-rpc

记录日期：2026-11-06。环境：Kotlin Toolchain 0.13.0，项目编译器 Kotlin 2.4.21，Ktor 3.6.0。

## 背景

当前的错误契约（见 [error-contract.md](error-contract.md)）用可序列化的 sealed `ChatError` 描述业务失败，`ChatException(error)` 负责在服务端和客户端抛出，路由和 `KtorChatClient` 负责 HTTP 转换。实验想回答两个问题：

1. Kotlin 的 Rich Errors（`T | Error` 错误联合类型）能否用来以返回值表达业务失败？
2. kotlinx-rpc 能否去掉手写的路由和 HTTP 客户端？它把异常传过网络时会发生什么？两者能否组合，让错误作为返回值经 RPC 传输？

## 结论

| 问题 | 结论 |
| --- | --- |
| Rich Errors 能否使用 | 不能。2.4.21 编译器不认识该语法；2.5.0-Beta2 中 `RichErrors` 是仅限编译器测试的特性，命令行无法开启，标准库也没有 `kotlin.RichError` |
| kRPC + sealed 返回值 | 可行。`@Rpc` 接口在 JVM、JS、linuxX64 上编译通过；JVM 往返测试中 sealed 返回值保留具体类型 |
| kRPC 传递异常 | 不保留类型。即使在 JVM 上，服务端抛出的自定义异常到客户端也变成 `DeserializedException`，只保留消息文字，类型、字段和 `cause` 全部丢失 |
| Rich Errors + kRPC 组合 | 目前无法验证，前提的 Rich Errors 不可用 |

因此当前不做改动：

- 保留现有错误契约。业务失败以异常传递在本项目中仍有必要（`atomicity.atomic` 依靠异常回滚）。
- 若采用 kRPC，业务失败必须出现在 RPC 方法的返回类型中，不能依赖抛出异常；`ChatClient` 的签名风格会从“返回结果或抛出 `ChatException`”变为“返回 sealed 结果”。服务端可以在 RPC 实现中捕获 `ChatException` 并转为返回值，`ChatService` 内部继续用异常保证回滚。
- Rich Errors 进入可用的实验阶段后，重新进行第 1 项实验。

## 实验 1：Rich Errors

语法依据 [KEEP-0462](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0462-rich-errors.md)：用软关键字 `error` 声明错误类型，返回类型写成联合类型。

```kotlin
error object NotInRoomError

fun probe(member: Boolean): String | NotInRoomError = if (member) "ok" else NotInRoomError
```

**Kotlin 2.4.21**：编译器的 `LanguageFeature` 中没有相关特性。上面的代码在解析阶段即失败：

```
ERROR: Syntax error: Expecting a top level declaration.
```

**Kotlin 2.5.0-Beta2**：其更新日志列出 Rich Errors 的解析器、解析、检查器和 FIR 树（KT-89096、KT-89098、KT-89103、KT-89097），编译器中存在语言特性 `RichErrors`。在 `modeling/module.yaml` 中设置：

```yaml
settings:
  kotlin:
    version: 2.5.0-Beta2
    freeCompilerArgs: [-XXLanguage:+RichErrors]
```

三个平台都拒绝开启：

```
ERROR: Language feature 'RichErrors' is test-only and cannot be enabled from command line
ERROR: Cannot access 'kotlin.RichError' which is a supertype of 'preferences.modeling.NotInRoomError'.
```

JS 与 linuxX64 报同样的 test-only 错误。该特性当前只在编译器自身的测试中可用，标准库尚未提供错误类型的公共父类。KEEP 中也没有说明 kotlinx.serialization 如何处理联合类型，因此“联合类型能否序列化”这一项暂时无法验证。

## 实验 2：kotlinx-rpc

版本：kotlinx-rpc 0.10.4。编译器插件版本必须与 Kotlin 版本精确对应，本次使用 `kotlinx-rpc-compiler-plugin-cli:2.4.21-0.10.4`；升级 Kotlin 时需要等待对应版本的插件。

Kotlin Toolchain 通过 `compilerPlugins` 接入第三方编译器插件，不需要 Gradle：

```yaml
product:
  type: kmp/lib
  platforms: [jvm, js, linuxX64]

settings:
  ktor:
    enabled: true
    version: 3.6.0
  kotlin:
    serialization: enabled
    compilerPlugins:
      - id: org.jetbrains.kotlinx.rpc
        dependency: org.jetbrains.kotlinx:kotlinx-rpc-compiler-plugin-cli:2.4.21-0.10.4

dependencies:
  - org.jetbrains.kotlinx:kotlinx-rpc-core:0.10.4
  - org.jetbrains.kotlinx:kotlinx-rpc-krpc-ktor-client:0.10.4
  - org.jetbrains.kotlinx:kotlinx-rpc-krpc-serialization-json:0.10.4

dependencies@jvm:
  - org.jetbrains.kotlinx:kotlinx-rpc-krpc-ktor-server:0.10.4

test-dependencies@jvm:
  - $ktor.server.testHost
```

共享接口用 sealed 类型代替 `List<String> | NotInRoom`，另加一个服务端会抛异常的方法：

```kotlin
@Serializable
sealed interface PullResult {
    @Serializable @SerialName("Messages")
    data class Messages(val messages: List<String>) : PullResult

    @Serializable @SerialName("NotInRoom")
    data object NotInRoom : PullResult
}

class ProbeChatException(val code: String) : IllegalStateException("probe failure: $code")

@Rpc
interface ProbeChat {
    suspend fun pull(member: Boolean): PullResult
    suspend fun failing(): String
}
```

JVM 测试使用 Ktor `testApplication`，服务端 `install(Krpc)` 并在 `rpc("/rpc")` 中注册实现，客户端 `createClient { installKrpc() }` 后通过 `rpc("ws://localhost/rpc").withService<ProbeChat>()` 调用：

```kotlin
assertEquals(PullResult.Messages(listOf("你好")), chat.pull(member = true))
assertIs<PullResult.NotInRoom>(chat.pull(member = false))
val thrown = runCatching { chat.failing() }.exceptionOrNull()
```

结果：

- 模块在 JVM、JS、linuxX64 上编译通过。
- 两次 `pull` 的 sealed 返回值都按具体类型还原。
- `failing()` 在客户端得到：

```
class=kotlinx.rpc.krpc.internal.DeserializedException
message=probe failure: NotInRoom
cause=null
isProbeChatException=false
```

只验证了 JVM 运行时。社区反馈和维护者说明表示，非 JVM 平台对异常的还原更受限（见参考资料），Web 端属于这种情况。

其他影响：

- kRPC 通过 WebSocket 传输，不再是 REST/JSON 接口，不能用 curl 直接调试；`StatusPages` 的状态码映射不再适用。
- 去掉的胶水是 `Server.kt` 中的聊天路由和 `KtorChatClient`；新增的是插件版本绑定和返回值风格的契约。

## 参考资料

- [KEEP-0462 Rich Errors](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0462-rich-errors.md)、[讨论 #487](https://github.com/Kotlin/KEEP/discussions/487)
- [Kotlin 2.5.0-Beta2 发布说明](https://github.com/JetBrains/kotlin/releases/tag/v2.5.0-Beta2)
- [kotlinx-rpc 异常传输讨论（Kotlin Slack）](https://slack-chats.kotlinlang.org/t/23208210/hello-we-are-seeing-that-exceptions-thrown-during-an-rpc-ses)
