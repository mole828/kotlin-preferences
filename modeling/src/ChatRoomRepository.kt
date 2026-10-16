package preferences.modeling

import preferences.modeling.types.ChatMessage
import preferences.modeling.types.RoomId
import preferences.modeling.types.UserId

interface ChatRoomRepository {
    suspend fun addMessage(chatMessage: ChatMessage): ChatMessage
    suspend fun pullMessage(userId: UserId, roomId: RoomId): List<ChatMessage>
}
