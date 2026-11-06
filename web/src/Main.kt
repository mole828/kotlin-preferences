package preferences.web

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import preferences.client.ChatSession
import preferences.client.KtorChatClient
import preferences.client.RoomState
import preferences.modeling.ChatError
import preferences.modeling.types.MessageContent
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
import web.dom.ElementId
import web.dom.document
import web.cssom.ClassName

/** Renders the latest value of [flow] and re-renders when it changes. */
private fun <T> useStateFlow(flow: StateFlow<T>): T {
    var value by useState(flow.value)
    useEffect(flow) { flow.collect { value = it } }
    return value
}

private fun removalNotice(error: ChatError): String = when (error) {
    ChatError.NotInRoom -> "你已不在该房间，请重新加入"
}

private val App = FC<Props> {
    val httpClient by useState { HttpClient(Js) }
    val scope by useState { MainScope() }
    val session by useState {
        ChatSession(KtorChatClient(httpClient, js("window.location.origin") as String), scope)
    }
    val ui = useStateFlow(session.state)
    var nickname by useState("")
    var roomName by useState("大厅")
    var draft by useState("")
    val joined = ui.room as? RoomState.Joined
    val busy = ui.busy
    val notice = (ui.room as? RoomState.NotJoined)?.removedBy?.let(::removalNotice)

    useEffectOnce {
        try { awaitCancellation() } finally { scope.cancel(); httpClient.close() }
    }

    fun join() {
        val newUser = nickname.trim()
        val newRoom = roomName.trim()
        if (newUser.isEmpty() || newRoom.isEmpty()) return
        scope.launch { session.join(UserId(newUser), RoomId(newRoom)) }
    }

    fun leave() {
        scope.launch {
            session.leave()
            draft = ""
        }
    }

    fun send() {
        val content = draft.trim()
        if (content.isEmpty()) return
        scope.launch {
            if (session.send(MessageContent(content))) draft = ""
        }
    }

    main {
        h1 { +"聊天室" }
        notice?.let { p { className = ClassName("notice"); +it } }
        div {
            className = ClassName("join-fields")
            label {
                +"昵称"; input {
                    value = nickname; placeholder = "昵称"; maxLength = 80
                    disabled = joined != null || busy; onChange = { nickname = it.target.value }
                }
            }
            label {
                +"房间"; input {
                    value = roomName; placeholder = "房间"; maxLength = 80
                    disabled = joined != null || busy; onChange = { roomName = it.target.value }
                }
            }
            if (joined == null) button {
                disabled = busy || nickname.trim().isEmpty() || roomName.trim().isEmpty()
                onClick = { join() }; +"加入房间"
            }
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
}

fun main() {
    val container = checkNotNull(document.getElementById(ElementId("root")))
    createRoot(container).render(App.create())
}
