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
import preferences.modeling.types.ChatMessage
import preferences.modeling.types.MessageContent
import preferences.modeling.types.MessageSeq
import preferences.modeling.types.RoomId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class ChatUiState(
    val room: RoomState = RoomState.NotJoined(),
    /** An action is in progress; further actions are ignored until it finishes. */
    val busy: Boolean = false,
)

sealed interface RoomState {
    /**
     * [removedBy] is the business failure that took the user out of the room, or null after joining nothing or leaving.
     * [ChatError.SessionExpired] means the sign-in itself ended, so the UI should ask the user to sign in again.
     */
    data class NotJoined(val removedBy: ChatError? = null) : RoomState

    data class Joined(val room: RoomId, val messages: List<ChatMessage>) : RoomState
}

/**
 * Client-side chat flow of a signed-in user, shared by every UI: joining, receiving the room's messages, sending
 * and leaving. UIs render [state] and decide how to present it. Receiving runs in [scope]; cancel it to stop the
 * session. Failures of the UI's own actions other than business failures propagate to the caller, as when calling
 * [ChatClient] directly; a broken message stream is reconnected after [reconnectDelay].
 * Call it from the UI's main dispatcher.
 */
class ChatSession(
    val client: ChatClient,
    private val scope: CoroutineScope,
    private val reconnectDelay: Duration = 2.seconds,
) {
    private val mutableState = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = mutableState.asStateFlow()

    private val actionLock = Mutex()
    private var receiving: Job? = null

    suspend fun join(room: RoomId) {
        if (state.value.room is RoomState.Joined) return
        exclusive {
            handlingRemoval {
                client.join(room)
                val joined = RoomState.Joined(room, messages = emptyList())
                mutableState.update { it.copy(room = joined) }
                startReceiving(joined)
            }
        }
    }

    suspend fun leave() {
        val joined = state.value.room as? RoomState.Joined ?: return
        exclusive {
            handlingRemoval {
                client.leave(joined.room)
                exitRoom(removedBy = null)
            }
        }
    }

    /** Returns true when the message was sent, so the UI can clear its draft. The message arrives through [state]. */
    suspend fun send(content: MessageContent): Boolean {
        val joined = state.value.room as? RoomState.Joined ?: return false
        var sent = false
        exclusive {
            handlingRemoval {
                client.send(joined.room, content)
                sent = true
            }
        }
        return sent
    }

    private fun startReceiving(joined: RoomState.Joined) {
        receiving?.cancel()
        receiving = scope.launch {
            handlingRemoval {
                while (true) {
                    try {
                        client.messages(joined.room, after = lastSeq(joined)).collect { append(joined, it) }
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
        (state.value.room as? RoomState.Joined)?.takeIf { it.room == joined.room }?.messages?.lastOrNull()?.seq

    private fun append(joined: RoomState.Joined, message: ChatMessage) {
        mutableState.update { current ->
            val room = current.room
            // Ignore a late message for a room the session has already left.
            if (room is RoomState.Joined && room.room == joined.room) {
                current.copy(room = room.copy(messages = room.messages + message))
            } else {
                current
            }
        }
    }

    // Another page of the same user may leave the room, and a sign-in expires: both return to the not-joined state
    // with the reason instead of failing. Sign-in failures cannot come from these actions.
    private suspend fun handlingRemoval(action: suspend () -> Unit) {
        try {
            action()
        } catch (e: ChatException) {
            when (e.error) {
                ChatError.NotInRoom, ChatError.SessionExpired -> exitRoom(removedBy = e.error)
                ChatError.UsernameTaken, ChatError.InvalidCredentials -> throw e
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
