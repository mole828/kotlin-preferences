package preferences.modeling

import preferences.modeling.types.ChatMessage
import preferences.modeling.types.RoomId

interface ChatRoomRepository {
    suspend fun addMessage(chatMessage: ChatMessage): ChatMessage
    suspend fun messagesIn(roomId: RoomId): List<ChatMessage>
}
