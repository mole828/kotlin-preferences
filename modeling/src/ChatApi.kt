package preferences.modeling

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import preferences.modeling.types.ChatMessage
import preferences.modeling.types.MessageContent
import preferences.modeling.types.Password
import preferences.modeling.types.RoomId
import preferences.modeling.types.UserId

// Requests carry no acting user: the server takes it from the bearer token.

@Serializable
data class Credentials(val username: UserId, val password: Password)

/** The response to registering or signing in; [token] authorizes the chat requests of [user]. */
@Serializable
data class SignedIn(val user: UserId, val token: String)

@Serializable
data class RoomRequest(val roomId: RoomId)

@Serializable
data class SendMessageRequest(val roomId: RoomId, val content: MessageContent)

/**
 * One event of [ChatClient.messages] over HTTP. The stream's response has started before a failure happens,
 * so a failure travels as the last event instead of as a status code.
 */
@Serializable
sealed interface MessageStreamEvent {
    @Serializable
    @SerialName("Message")
    data class Message(val message: ChatMessage) : MessageStreamEvent

    @Serializable
    @SerialName("Failure")
    data class Failure(val error: ChatError) : MessageStreamEvent
}

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

    /** The operation conflicts with existing data. */
    sealed interface Conflict : ChatError

    /** Who is calling could not be established; signing in again may help. */
    sealed interface NotAuthenticated : ChatError

    @Serializable
    @SerialName("NotInRoom")
    data object NotInRoom : NotPermitted {
        override val message get() = "请先加入房间"
    }

    @Serializable
    @SerialName("UsernameTaken")
    data object UsernameTaken : Conflict {
        override val message get() = "用户名已被注册"
    }

    /** Covers both an unknown user and a wrong password, so callers cannot probe which usernames exist. */
    @Serializable
    @SerialName("InvalidCredentials")
    data object InvalidCredentials : NotAuthenticated {
        override val message get() = "用户名或密码错误"
    }

    /** The sign-in is missing, invalid or expired. Only remote implementations report it. */
    @Serializable
    @SerialName("SessionExpired")
    data object SessionExpired : NotAuthenticated {
        override val message get() = "登录已失效，请重新登录"
    }
}

/** Thrown by every [ChatClient] implementation for a business failure; only [error] crosses HTTP. */
class ChatException(val error: ChatError) : Exception(error.message)
