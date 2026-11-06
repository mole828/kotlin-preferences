package preferences.modeling

import kotlinx.coroutines.flow.Flow
import preferences.modeling.types.ChatMessage
import preferences.modeling.types.MessageContent
import preferences.modeling.types.MessageSeq
import preferences.modeling.types.Password
import preferences.modeling.types.RoomId
import preferences.modeling.types.UserId

/**
 * Signing in, the shared entry to the chat. Implemented locally by [LocalChatGateway] and over HTTP by the client
 * module; both report business failures as [ChatException].
 */
interface ChatGateway {
    /** @throws ChatException with [ChatError.UsernameTaken] if [username] is already registered. */
    suspend fun register(username: UserId, password: Password): ChatClient

    /** @throws ChatException with [ChatError.InvalidCredentials], without telling an unknown user from a wrong password. */
    suspend fun login(username: UserId, password: Password): ChatClient
}

/**
 * What a signed-in [user] can do. The user is fixed when this object is obtained from a [ChatGateway],
 * so no method takes the acting user as a parameter.
 * Remote implementations may also fail with [ChatError.SessionExpired] once the sign-in expires.
 */
interface ChatClient {
    val user: UserId

    suspend fun join(roomId: RoomId)
    suspend fun leave(roomId: RoomId)

    /** @throws ChatException with [ChatError.NotInRoom] if [user] has not joined [roomId]. */
    suspend fun send(roomId: RoomId, content: MessageContent): ChatMessage

    /**
     * The room's messages after [after] (all of them when null), followed by new messages as they are committed,
     * each exactly once and in seq order. To resume after a disconnect, collect again after the last seq received.
     *
     * The flow does not complete by itself. It fails with [ChatException] carrying [ChatError.NotInRoom]
     * when [user] is not in the room, including after leaving while the flow is collected.
     */
    fun messages(roomId: RoomId, after: MessageSeq? = null): Flow<ChatMessage>
}
