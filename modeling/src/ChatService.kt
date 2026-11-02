package preferences.modeling

import preferences.modeling.types.ChatMessage
import preferences.modeling.types.RoomId
import preferences.modeling.types.UserId
import kotlin.time.Clock

class ChatService(
    private val memberships: MembershipRepository,
    private val rooms: ChatRoomRepository,
    private val atomicity: Atomicity,
) : ChatClient {
    override suspend fun join(userId: UserId, roomId: RoomId) {
        validateIds(userId, roomId)
        atomicity.atomic {
            if (memberships.addUserToRoom(userId, roomId)) {
                rooms.addMessage(
                    ChatMessage(userId, "${userId.value} 加入了群聊", Clock.System.now(), roomId)
                )
            }
        }
    }

    override suspend fun leave(userId: UserId, roomId: RoomId) {
        validateIds(userId, roomId)
        memberships.deleteUserFromRoom(userId, roomId)
    }

    override suspend fun send(request: SendMessageRequest): ChatMessage {
        validateIds(request.sender, request.roomId)
        require(request.content.isNotBlank()) { "消息不能为空" }
        require(request.content.length <= 4000) { "消息最多 4000 字符" }
        requireMember(request.sender, request.roomId)
        return rooms.addMessage(
            ChatMessage(request.sender, request.content, Clock.System.now(), request.roomId)
        )
    }

    override suspend fun pull(userId: UserId, roomId: RoomId): List<ChatMessage> {
        validateIds(userId, roomId)
        requireMember(userId, roomId)
        return rooms.messagesIn(roomId)
    }

    // Leaving between this check and the following read/write is equivalent to acting just before leaving.
    private suspend fun requireMember(userId: UserId, roomId: RoomId) {
        if (!memberships.isMember(userId, roomId)) throw NotInRoomException()
    }
}

private fun validateIds(userId: UserId, roomId: RoomId) {
    require(userId.value.isNotBlank() && userId.value.length <= 80) { "用户标识须为 1–80 字符" }
    require(roomId.value.isNotBlank() && roomId.value.length <= 80) { "房间标识须为 1–80 字符" }
}
