package preferences.modeling

import preferences.modeling.types.ChatMessage
import preferences.modeling.types.RoomId
import preferences.modeling.types.UserId

/**
 * The shared chat contract, implemented by the HTTP client and the server service.
 * Both implementations report business failures as [ChatException].
 */
interface ChatClient {
    suspend fun join(userId: UserId, roomId: RoomId)
    suspend fun leave(userId: UserId, roomId: RoomId)

    /** @throws ChatException with [ChatError.NotInRoom] if [SendMessageRequest.sender] has not joined the room. */
    suspend fun send(request: SendMessageRequest): ChatMessage

    /** @throws ChatException with [ChatError.NotInRoom] if [userId] has not joined [roomId]. */
    suspend fun pull(userId: UserId, roomId: RoomId): List<ChatMessage>
}
