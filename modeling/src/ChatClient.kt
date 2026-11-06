package preferences.modeling

import kotlinx.coroutines.flow.Flow
import preferences.modeling.types.ChatMessage
import preferences.modeling.types.MessageSeq
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

    /**
     * The room's messages after [after] (all of them when null), followed by new messages as they are committed,
     * each exactly once and in seq order. To resume after a disconnect, collect again after the last seq received.
     *
     * The flow does not complete by itself. It fails with [ChatException] carrying [ChatError.NotInRoom]
     * when [userId] is not in the room, including after leaving while the flow is collected.
     */
    fun messages(userId: UserId, roomId: RoomId, after: MessageSeq? = null): Flow<ChatMessage>
}
