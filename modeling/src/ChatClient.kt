package preferences.modeling

import preferences.modeling.types.ChatMessage
import preferences.modeling.types.RoomId
import preferences.modeling.types.UserId
import kotlin.time.Instant

/** The shared chat contract, implemented by the HTTP client and the server service. */
interface ChatClient {
    suspend fun join(userId: UserId, roomId: RoomId)
    suspend fun leave(userId: UserId, roomId: RoomId)
    suspend fun send(request: SendMessageRequest): ChatMessage
    suspend fun pull(userId: UserId, roomId: RoomId, after: Instant? = null): List<ChatMessage>
}

class ChatClientException(val statusCode: Int, message: String) : RuntimeException(message)
