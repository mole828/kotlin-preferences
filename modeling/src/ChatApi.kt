package preferences.modeling

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import preferences.modeling.types.MessageContent
import preferences.modeling.types.RoomId
import preferences.modeling.types.UserId

@Serializable
data class RoomMembershipRequest(val userId: UserId, val roomId: RoomId)

@Serializable
data class SendMessageRequest(val sender: UserId, val roomId: RoomId, val content: MessageContent)

/**
 * Business failures of the [ChatClient] contract. The HTTP response body carries them as JSON.
 *
 * Failures are grouped by what happened, so callers and the backend can handle a whole category;
 * add a category only when a real failure belongs to it.
 */
@Serializable
sealed interface ChatError {
    val message: String

    /** The user may not perform this operation in the current situation. */
    sealed interface NotPermitted : ChatError

    @Serializable
    @SerialName("NotInRoom")
    data object NotInRoom : NotPermitted {
        override val message get() = "请先加入房间"
    }
}

/** Thrown by every [ChatClient] implementation for a business failure; only [error] crosses HTTP. */
class ChatException(val error: ChatError) : Exception(error.message)
