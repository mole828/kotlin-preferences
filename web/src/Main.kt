package preferences.web

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import preferences.modeling.SendMessageRequest
import preferences.modeling.client.KtorChatClient
import preferences.modeling.types.ChatMessage
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

private val App = FC<Props> {
    val httpClient by useState { HttpClient(Js) }
    val chatClient by useState { KtorChatClient(httpClient, js("window.location.origin") as String) }
    val scope by useState { MainScope() }
    var nickname by useState("")
    var roomName by useState("大厅")
    var draft by useState("")
    var user by useState<UserId?>(null)
    var room by useState<RoomId?>(null)
    var messages by useState<List<ChatMessage>>(emptyList())
    var busy by useState(false)
    val joined = user != null && room != null

    useEffectOnce {
        try { awaitCancellation() } finally { scope.cancel(); httpClient.close() }
    }

    useEffect(joined) {
        val joinedUser = user ?: return@useEffect
        val joinedRoom = room ?: return@useEffect
        while (isActive) {
            messages = chatClient.pull(joinedUser, joinedRoom)
            delay(2500)
        }
    }

    fun runExclusive(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { action() } finally { busy = false }
        }
    }

    fun join() {
        val newUser = nickname.trim()
        val newRoom = roomName.trim()
        if (joined || newUser.isEmpty() || newRoom.isEmpty()) return
        runExclusive {
            chatClient.join(UserId(newUser), RoomId(newRoom))
            messages = emptyList(); user = UserId(newUser); room = RoomId(newRoom)
        }
    }

    fun leave() {
        val currentUser = user ?: return
        val currentRoom = room ?: return
        runExclusive {
            chatClient.leave(currentUser, currentRoom)
            user = null; room = null; messages = emptyList(); draft = ""
        }
    }

    fun send() {
        val currentUser = user ?: return
        val currentRoom = room ?: return
        val content = draft.trim()
        if (content.isEmpty()) return
        runExclusive {
            chatClient.send(SendMessageRequest(currentUser, currentRoom, MessageContent(content)))
            draft = ""
            messages = chatClient.pull(currentUser, currentRoom)
        }
    }

    main {
        h1 { +"聊天室" }
        div {
            className = ClassName("join-fields")
            label {
                +"昵称"; input {
                    value = nickname; placeholder = "昵称"; maxLength = 80
                    disabled = joined || busy; onChange = { nickname = it.target.value }
                }
            }
            label {
                +"房间"; input {
                    value = roomName; placeholder = "房间"; maxLength = 80
                    disabled = joined || busy; onChange = { roomName = it.target.value }
                }
            }
            if (!joined) button {
                disabled = busy || nickname.trim().isEmpty() || roomName.trim().isEmpty()
                onClick = { join() }; +"加入房间"
            }
        }
        if (joined) {
            div {
                className = ClassName("messages")
                messages.forEach { message -> div {
                    className = ClassName("message"); key = Key(message.id.value.toString())
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
