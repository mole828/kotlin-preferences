package preferences.web

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import js.reflect.unsafeCast
import preferences.modeling.ChatClientException
import preferences.modeling.client.KtorChatClient
import preferences.modeling.SendMessageRequest
import preferences.modeling.types.ChatMessage
import preferences.modeling.types.RoomId
import preferences.modeling.types.UserId
import react.FC
import react.Key
import react.Props
import react.create
import react.dom.client.createRoot
import react.dom.aria.AriaRole
import react.dom.html.ReactHTML.aside
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.form
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.header
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.label
import react.dom.html.ReactHTML.main
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.section
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.strong
import react.dom.html.ReactHTML.textarea
import react.dom.html.ReactHTML.time
import react.useEffect
import react.useEffectOnce
import react.useRef
import react.useState
import web.dom.ElementId
import web.dom.document
import web.cssom.ClassName
import js.date.Date
import kotlin.time.Instant

private val chatHttpClient = HttpClient(Js)
private val chatClient = KtorChatClient(chatHttpClient, js("window.location.origin") as String)
private val actionScope = MainScope()

private fun messageFor(error: ChatClientException): String =
    error.message?.takeIf { it.isNotBlank() } ?: when (error.statusCode) {
        400 -> "提交内容不符合要求，请检查昵称、房间名和消息内容。"
        403 -> "你还没有加入这个房间，请先重新加入。"
        404 -> "找不到聊天接口，请确认后端版本与前端一致。"
        in 500..599 -> "聊天服务暂时遇到问题，请稍后重试。"
        else -> "请求没有成功（HTTP ${error.statusCode}），请稍后重试。"
    }

private fun messageForFailure(failure: Throwable): String =
    (failure as? ChatClientException)?.let(::messageFor)
        ?: "暂时无法连接聊天服务，请检查服务是否启动后重试。"

private fun formatTime(value: Instant): String {
    val date = Date(value.toString())
    return "${date.getHours().toString().padStart(2, '0')}:${date.getMinutes().toString().padStart(2, '0')}"
}

private fun timeCursor(messages: List<ChatMessage>): Instant? =
    messages.maxByOrNull { it.time }?.time

private val App = FC<Props> {
    var nickname by useState("")
    var roomName by useState("大厅")
    var activeUser by useState<UserId?>(null)
    var activeRoom by useState<RoomId?>(null)
    var messages by useState(emptyList<ChatMessage>())
    val messagesRef = useRef<List<ChatMessage>>(emptyList())
    val pollCursorRef = useRef<Instant>(null)
    var draft by useState("")
    val draftRevision = useRef(0)
    val sessionGeneration = useRef(0)
    val sendGeneration = useRef(0)
    var loadingHistory by useState(false)
    var joining by useState(false)
    var leaving by useState(false)
    var sending by useState(false)
    var error by useState<String?>(null)
    var notice by useState<String?>(null)

    val joined = activeUser != null && activeRoom != null

    fun clearMessages() {
        messagesRef.current = emptyList()
        pollCursorRef.current = null
        messages = emptyList()
    }

    fun mergeMessages(incoming: List<ChatMessage>) {
        val byId = ((messagesRef.current ?: emptyList()) + incoming).associateBy { it.id }
        val merged = byId.values.sortedWith(compareBy<ChatMessage>({ it.time }, { it.id }))
        messagesRef.current = merged
        messages = merged
    }

    useEffectOnce {
        try {
            awaitCancellation()
        } finally {
            actionScope.cancel()
            chatHttpClient.close()
        }
    }

    fun join() {
        val userValue = nickname.trim()
        val roomValue = roomName.trim()
        if (userValue.isEmpty() || roomValue.isEmpty() || joining) return
        val user = UserId(userValue)
        val room = RoomId(roomValue)
        error = null
        notice = null
        joining = true
        actionScope.launch {
            try {
                chatClient.join(user, room)
                clearMessages()
                sessionGeneration.current = (sessionGeneration.current ?: 0) + 1
                sendGeneration.current = (sendGeneration.current ?: 0) + 1
                sending = false
                loadingHistory = true
                activeUser = user
                activeRoom = room
                notice = "已加入「${room.value}」，可以开始聊天了。"
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                error = messageForFailure(failure)
            } finally {
                joining = false
            }
        }
    }

    fun leave() {
        val user = activeUser ?: return
        val room = activeRoom ?: return
        if (leaving) return
        leaving = true
        error = null
        actionScope.launch {
            try {
                chatClient.leave(user, room)
                sessionGeneration.current = (sessionGeneration.current ?: 0) + 1
                sendGeneration.current = (sendGeneration.current ?: 0) + 1
                sending = false
                activeUser = null
                activeRoom = null
                clearMessages()
                notice = "你已离开房间。"
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                error = messageForFailure(failure)
            } finally {
                leaving = false
            }
        }
    }

    useEffect(activeUser?.value, activeRoom?.value) {
        val user = activeUser
        val room = activeRoom
        if (user == null || room == null) return@useEffect

        val session = sessionGeneration.current ?: 0
        loadingHistory = true
        while (isActive) {
            try {
                val incoming = chatClient.pull(user, room, pollCursorRef.current)
                if (!isActive || sessionGeneration.current != session) break
                mergeMessages(incoming)
                val newestPulled = timeCursor(incoming)
                val currentCursor = pollCursorRef.current
                if (newestPulled != null && (currentCursor == null || newestPulled > currentCursor)) {
                    pollCursorRef.current = newestPulled
                }
                loadingHistory = false
                error = null
                delay(2500)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                if (!isActive || sessionGeneration.current != session) break
                loadingHistory = false
                error = messageForFailure(failure)
                delay(4000)
            }
        }
    }

    useEffect(messages.size, joined) {
        val history: dynamic = js("document.querySelector('.message-area')")
        if (history != null) history.scrollTop = history.scrollHeight
    }

    fun send() {
        val user = activeUser ?: return
        val room = activeRoom ?: return
        val content = draft.trim()
        if (content.isEmpty() || sending) return
        error = null
        sending = true
        val revision = draftRevision.current ?: 0
        val session = sessionGeneration.current ?: 0
        val sendToken = (sendGeneration.current ?: 0) + 1
        sendGeneration.current = sendToken
        actionScope.launch {
            try {
                val sent = chatClient.send(SendMessageRequest(sender = user, roomId = room, content = content))
                if (sessionGeneration.current != session) return@launch
                mergeMessages(listOf(sent))
                if ((draftRevision.current ?: 0) == revision) {
                    draftRevision.current = revision + 1
                    draft = ""
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                if (sessionGeneration.current == session) error = messageForFailure(failure)
            } finally {
                if (sendGeneration.current == sendToken) sending = false
            }
        }
    }

    main {
        div {
            className = ClassName("app-shell")
            aside {
                className = ClassName("sidebar")
                div {
                    className = ClassName("brand")
                    div { className = ClassName("brand-mark"); +"聊" }
                    div {
                        strong { +"拾光 · 聊天室" }
                        span { +"轻松聊点新鲜事" }
                    }
                }
                div {
                    className = ClassName("side-section-heading")
                    span { +"你的空间" }
                    span { className = ClassName("live-indicator"); +"在线" }
                }
                div {
                    className = ClassName(if (joined) "room-card active" else "room-card")
                    div { className = ClassName("room-icon"); +"#" }
                    div {
                        strong { +(activeRoom?.value ?: roomName.ifBlank { "选择房间" }) }
                        span { +(if (joined) "你正在这里" else "加入后查看消息") }
                    }
                    span { className = ClassName("room-chevron"); +"›" }
                }
                div {
                    className = ClassName("sidebar-note")
                    span { className = ClassName("note-sparkle"); +"✦" }
                    p { +"每段对话，都是一次新的相遇。" }
                }
                div {
                    className = ClassName("sidebar-footer")
                    span { className = ClassName("avatar small"); +(nickname.trim().firstOrNull()?.uppercase() ?: "你") }
                    div {
                        strong { +(nickname.trim().ifBlank { "还未加入" }) }
                        span { +(if (joined) "当前访客" else "输入昵称开始聊天") }
                    }
                    span { className = ClassName(if (joined) "status-dot" else "status-dot muted") }
                }
            }

            section {
                className = ClassName("chat-panel")
                header {
                    className = ClassName("topbar")
                    div {
                        div { className = ClassName("eyebrow"); +"你的日常会客厅" }
                        h1 { +(activeRoom?.value ?: "欢迎来到拾光") }
                        p { +(if (joined) "和房间里的朋友分享此刻" else "加入一个房间，开始一场轻松的对话") }
                    }
                    div {
                        className = ClassName("topbar-actions")
                        div {
                            className = ClassName("connection-pill")
                            span { className = ClassName(if (joined) "status-dot" else "status-dot muted") }
                            span { +(if (joined) "已连接" else "未连接") }
                        }
                        if (joined) {
                            button {
                                className = ClassName("leave-button")
                                type = unsafeCast("button")
                                disabled = leaving
                                onClick = { leave() }
                                +(if (leaving) "正在离开…" else "离开房间")
                            }
                        }
                    }
                }

                if (!joined) {
                    div {
                        className = ClassName("join-banner")
                        div {
                            className = ClassName("join-copy")
                            span { className = ClassName("join-icon"); +"✦" }
                            div {
                                strong { +"准备好加入对话了吗？" }
                                p { +"设置一个昵称和房间名，就可以查看历史消息并参与聊天。" }
                            }
                        }
                        form {
                            className = ClassName("join-form")
                            onSubmit = { event -> event.preventDefault(); join() }
                            label {
                                +"你的昵称"
                                input {
                                    type = unsafeCast("text")
                                    maxLength = 80
                                    value = nickname
                                    placeholder = "例如：小满"
                                    onChange = { event -> nickname = event.target.value }
                                }
                            }
                            label {
                                +"房间名"
                                input {
                                    type = unsafeCast("text")
                                    maxLength = 80
                                    value = roomName
                                    placeholder = "例如：大厅"
                                    onChange = { event -> roomName = event.target.value }
                                }
                            }
                            button {
                                className = ClassName("join-button")
                                type = unsafeCast("submit")
                                disabled = joining || nickname.trim().isEmpty() || roomName.trim().isEmpty()
                                +(if (joining) "正在加入…" else "加入房间  →")
                            }
                        }
                    }
                }

                if (error != null || notice != null) {
                    div {
                        className = ClassName(if (error != null) "feedback error" else "feedback notice")
                        role = AriaRole.alert
                        span { +(if (error != null) "!" else "✓") }
                        p { +(error ?: notice ?: "") }
                        button {
                            type = unsafeCast("button")
                            ariaLabel = "关闭提示"
                            onClick = { error = null; notice = null }
                            +"×"
                        }
                    }
                }

                div {
                    className = ClassName("message-area")
                    div {
                        className = ClassName("message-list")
                        if (!joined) {
                            div {
                                className = ClassName("empty-state")
                                div { className = ClassName("empty-art"); +"☼" }
                                h2 { +"让故事从这里开始" }
                                p { +"加入房间后，大家的消息会出现在这里。" }
                            }
                        } else if (loadingHistory && messages.isEmpty()) {
                            div {
                                className = ClassName("empty-state loading-state")
                                div { className = ClassName("loader") }
                                h2 { +"正在整理房间消息…" }
                                p { +"很快就好" }
                            }
                        } else if (messages.isEmpty()) {
                            div {
                                className = ClassName("empty-state")
                                div { className = ClassName("empty-art"); +"☼" }
                                h2 { +"这里还很安静" }
                                p { +"发出第一条消息，开启今天的对话。" }
                            }
                        } else {
                            div { className = ClassName("date-divider"); span { +"消息记录" } }
                            messages.forEach { message ->
                                val mine = message.sender == activeUser
                                div {
                                    className = ClassName(if (mine) "message-row mine" else "message-row")
                                    key = Key(message.id.toString())
                                    span { className = ClassName(if (mine) "avatar message-avatar mine-avatar" else "avatar message-avatar"); +(message.sender.value.firstOrNull()?.uppercase() ?: "聊") }
                                    div {
                                        className = ClassName("message-content")
                                        div {
                                            className = ClassName("message-meta")
                                            strong { +message.sender.value }
                                            time { +formatTime(message.time) }
                                        }
                                        p { className = ClassName("message-bubble"); +message.content }
                                    }
                                }
                            }
                        }
                    }
                }

                div {
                    className = ClassName("composer-wrap")
                    form {
                        className = ClassName(if (joined) "composer" else "composer disabled")
                        onSubmit = { event -> event.preventDefault(); send() }
                        textarea {
                            value = draft
                            maxLength = 4000
                            placeholder = if (joined) "写下你的想法…" else "加入房间后即可发送消息"
                            disabled = !joined
                            rows = 1
                            onChange = { event ->
                                draftRevision.current = (draftRevision.current ?: 0) + 1
                                draft = event.target.value
                            }
                        }
                        div {
                            className = ClassName("composer-bottom")
                            span { +(if (joined) "按下发送，与大家分享这一刻" else "你的消息只会发送到当前房间") }
                            button {
                                className = ClassName("send-button")
                                type = unsafeCast("submit")
                                disabled = !joined || sending || draft.trim().isEmpty()
                                span { +(if (sending) "发送中…" else "发送") }
                                span { +"↗" }
                            }
                        }
                    }
                    div { className = ClassName("disclaimer"); +"使用昵称加入 · 当前版本采用访客身份" }
                }
            }
        }
    }
}

fun main() {
    val container = checkNotNull(document.getElementById(ElementId("root")))
    createRoot(container).render(App.create())
}
