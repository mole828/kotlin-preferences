package preferences.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import preferences.modeling.ChatClient
import preferences.modeling.ChatError
import preferences.modeling.ChatException
import preferences.modeling.SendMessageRequest
import preferences.modeling.types.ChatMessage
import preferences.modeling.types.MessageContent
import preferences.modeling.types.MessageSeq
import preferences.modeling.types.RoomId
import preferences.modeling.types.UserId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class ChatUiState(
    val room: RoomState = RoomState.NotJoined(),
    /** An action is in progress; further actions are ignored until it finishes. */
    val busy: Boolean = false,
)

sealed interface RoomState {
    /** [removedBy] is the business failure that took the user out of the room, or null after joining nothing or leaving. */
    data class NotJoined(val removedBy: ChatError? = null) : RoomState

    data class Joined(val user: UserId, val room: RoomId, val messages: List<ChatMessage>) : RoomState
}

/**
 * Client-side chat flow shared by every UI: joining, receiving the room's messages, sending and leaving.
 * UIs render [state] and decide how to present it. Receiving runs in [scope]; cancel it to stop the session.
 * Failures of the UI's own actions other than business failures propagate to the caller, as when calling
 * [ChatClient] directly; a broken message stream is reconnected after [reconnectDelay].
 * Call it from the UI's main dispatcher.
 */
class ChatSession(
    private val client: ChatClient,
    private val scope: CoroutineScope,
    private val reconnectDelay: Duration = 2.seconds,
) {
    private val mutableState = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = mutableState.asStateFlow()

    private val actionLock = Mutex()
    private var receiving: Job? = null

    suspend fun join(user: UserId, room: RoomId) {
        if (state.value.room is RoomState.Joined) return
        exclusive {
            client.join(user, room)
            val joined = RoomState.Joined(user, room, messages = emptyList())
            mutableState.update { it.copy(room = joined) }
            startReceiving(joined)
        }
    }

    suspend fun leave() {
        val joined = state.value.room as? RoomState.Joined ?: return
        exclusive {
            client.leave(joined.user, joined.room)
            exitRoom(removedBy = null)
        }
    }

    /** Returns true when the message was sent, so the UI can clear its draft. The message arrives through [state]. */
    suspend fun send(content: MessageContent): Boolean {
        val joined = state.value.room as? RoomState.Joined ?: return false
        var sent = false
        exclusive {
            asMember {
                client.send(SendMessageRequest(joined.user, joined.room, content))
                sent = true
            }
        }
        return sent
    }

    private fun startReceiving(joined: RoomState.Joined) {
        receiving?.cancel()
        receiving = scope.launch {
            asMember {
                while (true) {
                    try {
                        client.messages(joined.user, joined.room, after = lastSeq(joined)).collect { append(joined, it) }
                    } catch (e: Exception) {
                        if (e is ChatException || e is CancellationException) throw e
                    }
                    // The connection broke or the server closed the stream: resume after the last message received.
                    delay(reconnectDelay)
                }
            }
        }
    }

    private fun lastSeq(joined: RoomState.Joined): MessageSeq? =
        (state.value.room as? RoomState.Joined)?.takeIf { it.isSameRoom(joined) }?.messages?.lastOrNull()?.seq

    private fun append(joined: RoomState.Joined, message: ChatMessage) {
        mutableState.update { current ->
            val room = current.room
            // Ignore a late message for a room the session has already left.
            if (room is RoomState.Joined && room.isSameRoom(joined)) {
                current.copy(room = room.copy(messages = room.messages + message))
            } else {
                current
            }
        }
    }

    private fun RoomState.Joined.isSameRoom(other: RoomState.Joined) = user == other.user && room == other.room

    // Another page with the same nickname may leave the room; return to the not-joined state instead of failing.
    private suspend fun asMember(action: suspend () -> Unit) {
        try {
            action()
        } catch (e: ChatException) {
            when (e.error) {
                ChatError.NotInRoom -> exitRoom(removedBy = e.error)
            }
        }
    }

    private fun exitRoom(removedBy: ChatError?) {
        mutableState.update { it.copy(room = RoomState.NotJoined(removedBy)) }
        receiving?.cancel()
        receiving = null
    }

    private suspend fun exclusive(action: suspend () -> Unit) {
        if (!actionLock.tryLock()) return
        mutableState.update { it.copy(busy = true) }
        try {
            action()
        } finally {
            mutableState.update { it.copy(busy = false) }
            actionLock.unlock()
        }
    }
}
