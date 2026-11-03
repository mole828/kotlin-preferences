package preferences.modeling

import preferences.modeling.types.ChatMessage
import preferences.modeling.types.MessageContent
import preferences.modeling.types.RoomId
import preferences.modeling.types.UserId
import kotlin.time.Instant

interface ChatRoomRepository {
    suspend fun addMessage(roomId: RoomId, sender: UserId, content: MessageContent, time: Instant): ChatMessage
    suspend fun messagesIn(roomId: RoomId): List<ChatMessage>
}
