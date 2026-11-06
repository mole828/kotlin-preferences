package preferences.web

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import preferences.client.ChatSession
import preferences.client.KtorChatGateway
import preferences.client.RoomState
import preferences.modeling.ChatClient
import preferences.modeling.ChatError
import preferences.modeling.ChatException
import preferences.modeling.ChatGateway
import preferences.modeling.types.MessageContent
import preferences.modeling.types.Password
import preferences.modeling.types.RoomId
import preferences.modeling.types.UserId
import react.FC
import react.Key
import react.Props
import react.create
import react.dom.client.createRoot
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.label
import react.dom.html.ReactHTML.main
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.strong
import react.dom.html.ReactHTML.textarea
import react.useEffect
import react.useEffectOnce
import react.useState
import web.cssom.ClassName
import web.dom.ElementId
import web.dom.document
import web.html.InputType
import web.html.password

/** Renders the latest value of [flow] and re-renders when it changes. */
private fun <T> useStateFlow(flow: StateFlow<T>): T {
    var value by useState(flow.value)
    useEffect(flow) { flow.collect { value = it } }
    return value
}

private fun noticeFor(error: ChatError): String = when (error) {
    ChatError.NotInRoom -> "你已不在该房间，请重新加入"
    ChatError.SessionExpired -> "登录已失效，请重新登录"
    ChatError.UsernameTaken -> "用户名已被注册"
    ChatError.InvalidCredentials -> "用户名或密码错误"
}

private val App = FC<Props> {
    val httpClient by useState { HttpClient(Js) }
    val scope by useState { MainScope() }
    val gateway by useState { KtorChatGateway(httpClient, js("window.location.origin") as String) }
    var session by useState<ChatSession?>(null)
    var notice by useState<String?>(null)

    useEffectOnce {
        try { awaitCancellation() } finally { scope.cancel(); httpClient.close() }
    }

    main {
        h1 { +"聊天室" }
        notice?.let { p { className = ClassName("notice"); +it } }
        val current = session
        if (current == null) {
            SignInForm {
                this.gateway = gateway
                this.scope = scope
                onSignedIn = { client ->
                    notice = null
                    session = ChatSession(client, scope)
                }
                onFailed = { notice = it }
            }
        } else {
            ChatRoom {
                this.session = current
                this.scope = scope
                onNotice = { notice = it }
                onSignedOut = { session = null }
            }
        }
    }
}

private external interface SignInFormProps : Props {
    var gateway: ChatGateway
    var scope: CoroutineScope
    var onSignedIn: (ChatClient) -> Unit
    var onFailed: (String) -> Unit
}

private val SignInForm = FC<SignInFormProps> { props ->
    var username by useState("")
    var password by useState("")
    var busy by useState(false)
    val valid = username.trim().length in 1..80 && password.length in 8..128

    fun signIn(register: Boolean) {
        if (!valid || busy) return
        busy = true
        props.scope.launch {
            try {
                val user = UserId(username.trim())
                val client = if (register) {
                    props.gateway.register(user, Password(password))
                } else {
                    props.gateway.login(user, Password(password))
                }
                props.onSignedIn(client)
            } catch (e: ChatException) {
                props.onFailed(noticeFor(e.error))
            } finally {
                busy = false
            }
        }
    }

    div {
        className = ClassName("join-fields")
        label {
            +"用户名"; input {
                value = username; placeholder = "用户名"; maxLength = 80
                disabled = busy; onChange = { username = it.target.value }
            }
        }
        label {
            +"密码"; input {
                type = InputType.password; value = password; placeholder = "至少 8 位"; maxLength = 128
                disabled = busy; onChange = { password = it.target.value }
            }
        }
        button { disabled = busy || !valid; onClick = { signIn(register = false) }; +"登录" }
        button { disabled = busy || !valid; onClick = { signIn(register = true) }; +"注册" }
    }
}

private external interface ChatRoomProps : Props {
    var session: ChatSession
    var scope: CoroutineScope
    var onNotice: (String?) -> Unit
    var onSignedOut: () -> Unit
}

private val ChatRoom = FC<ChatRoomProps> { props ->
    val session = props.session
    val ui = useStateFlow(session.state)
    var roomName by useState("大厅")
    var draft by useState("")
    val joined = ui.room as? RoomState.Joined
    val busy = ui.busy
    val removedBy = (ui.room as? RoomState.NotJoined)?.removedBy

    useEffect(removedBy) {
        props.onNotice(removedBy?.let(::noticeFor))
        if (removedBy == ChatError.SessionExpired) props.onSignedOut()
    }

    fun join() {
        val room = roomName.trim()
        if (room.isEmpty()) return
        props.scope.launch { session.join(RoomId(room)) }
    }

    fun leave() {
        props.scope.launch {
            session.leave()
            draft = ""
        }
    }

    fun send() {
        val content = draft.trim()
        if (content.isEmpty()) return
        props.scope.launch {
            if (session.send(MessageContent(content))) draft = ""
        }
    }

    fun signOut() {
        props.scope.launch {
            session.leave()
            props.onNotice(null)
            props.onSignedOut()
        }
    }

    div {
        className = ClassName("join-fields")
        p { +"已登录：${session.client.user.value}" }
        label {
            +"房间"; input {
                value = roomName; placeholder = "房间"; maxLength = 80
                disabled = joined != null || busy; onChange = { roomName = it.target.value }
            }
        }
        if (joined == null) button {
            disabled = busy || roomName.trim().isEmpty()
            onClick = { join() }; +"加入房间"
        }
        button { disabled = busy; onClick = { signOut() }; +"退出登录" }
    }
    if (joined != null) {
        div {
            className = ClassName("messages")
            joined.messages.forEach { message -> div {
                className = ClassName("message"); key = Key(message.seq.value.toString())
                strong { +message.sender.value }; p { +message.content.value }
            } }
        }
        div {
            className = ClassName("composer")
            textarea {
                value = draft; placeholder = "消息"; maxLength = 4000; rows = 3
                disabled = busy; onChange = { draft = it.target.value }
            }
            button { disabled = busy || draft.trim().isEmpty(); onClick = { send() }; +"发送" }
            button { disabled = busy; onClick = { leave() }; +"离开房间" }
        }
    }
}

fun main() {
    val container = checkNotNull(document.getElementById(ElementId("root")))
    createRoot(container).render(App.create())
}
