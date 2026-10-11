package preferences.modeling

import preferences.modeling.types.ChatMessage
import preferences.modeling.types.RoomId
import preferences.modeling.types.UserId
import kotlin.time.Instant
import kotlin.time.Clock

class ChatService(
    private val memberships: MembershipService,
    private val messages: ChatRoomRepository,
) : ChatClient {
    override suspend fun join(userId: UserId, roomId: RoomId) {
        if (memberships.join(userId, roomId)) {
            messages.addMessage(
                ChatMessage(userId, "${userId.value} 加入了群聊", Clock.System.now(), roomId)
            )
        }
    }

    override suspend fun leave(userId: UserId, roomId: RoomId) {
        memberships.leave(userId, roomId)
    }

    override suspend fun send(request: SendMessageRequest): ChatMessage = send(
        ChatMessage(request.sender, request.content, Clock.System.now(), request.roomId)
    )

    suspend fun send(message: ChatMessage): ChatMessage {
        validateIds(message.sender, message.roomId)
        require(message.content.isNotBlank()) { "消息不能为空" }
        require(message.content.length <= 4000) { "消息最多 4000 字符" }
        return messages.addMessage(message)
    }

    // Inclusive boundary: clients merge by message id, so equal timestamps are not lost.
    override suspend fun pull(userId: UserId, roomId: RoomId, after: Instant?): List<ChatMessage> {
        validateIds(userId, roomId)
        return messages.pullMessage(userId, roomId, after)
    }
}

internal fun validateIds(userId: UserId, roomId: RoomId) {
    require(userId.value.isNotBlank() && userId.value.length <= 80) { "用户标识须为 1–80 字符" }
    require(roomId.value.isNotBlank() && roomId.value.length <= 80) { "房间标识须为 1–80 字符" }
}
