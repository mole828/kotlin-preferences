package preferences.client

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
import preferences.modeling.types.RoomId
import preferences.modeling.types.UserId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

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
 * Client-side chat flow shared by every UI: joining, polling the room, sending and leaving.
 * UIs render [state] and decide how to present it. Polling runs in [scope]; cancel it to stop the session.
 * Failures other than business failures propagate to the caller, as when calling [ChatClient] directly.
 * Call it from the UI's main dispatcher.
 */
class ChatSession(
    private val client: ChatClient,
    private val scope: CoroutineScope,
    private val pollInterval: Duration = 2500.milliseconds,
) {
    private val mutableState = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = mutableState.asStateFlow()

    private val actionLock = Mutex()
    private var polling: Job? = null

    suspend fun join(user: UserId, room: RoomId) {
        if (state.value.room is RoomState.Joined) return
        exclusive {
            client.join(user, room)
            val joined = RoomState.Joined(user, room, messages = emptyList())
            mutableState.update { it.copy(room = joined) }
            startPolling(joined)
        }
    }

    suspend fun leave() {
        val joined = state.value.room as? RoomState.Joined ?: return
        exclusive {
            client.leave(joined.user, joined.room)
            exitRoom(removedBy = null)
        }
    }

    /** Returns true when the message was sent, so the UI can clear its draft. */
    suspend fun send(content: MessageContent): Boolean {
        val joined = state.value.room as? RoomState.Joined ?: return false
        var sent = false
        exclusive {
            asMember {
                client.send(SendMessageRequest(joined.user, joined.room, content))
                sent = true
                refresh(joined)
            }
        }
        return sent
    }

    private fun startPolling(joined: RoomState.Joined) {
        polling?.cancel()
        polling = scope.launch {
            asMember {
                while (true) {
                    refresh(joined)
                    delay(pollInterval)
                }
            }
        }
    }

    private suspend fun refresh(joined: RoomState.Joined) {
        val messages = client.pull(joined.user, joined.room)
        mutableState.update { current ->
            val room = current.room
            // Ignore a late result for a room the session has already left.
            if (room is RoomState.Joined && room.user == joined.user && room.room == joined.room) {
                current.copy(room = room.copy(messages = messages))
            } else {
                current
            }
        }
    }

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
        polling?.cancel()
        polling = null
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
