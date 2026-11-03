package preferences.modeling

import preferences.modeling.types.ChatMessage
import preferences.modeling.types.MessageContent
import preferences.modeling.types.RoomId
import preferences.modeling.types.UserId
import kotlin.time.Clock

class ChatService(
    private val memberships: MembershipRepository,
    private val rooms: ChatRoomRepository,
    private val atomicity: Atomicity,
) : ChatClient {
    override suspend fun join(userId: UserId, roomId: RoomId) {
        atomicity.atomic {
            if (memberships.addUserToRoom(userId, roomId)) {
                rooms.addMessage(
                    roomId = roomId,
                    sender = userId,
                    content = MessageContent("${userId.value} 加入了群聊"),
                    time = Clock.System.now(),
                )
            }
        }
    }

    override suspend fun leave(userId: UserId, roomId: RoomId) {
        memberships.deleteUserFromRoom(userId, roomId)
    }

    override suspend fun send(request: SendMessageRequest): ChatMessage {
        requireMember(request.sender, request.roomId)
        return rooms.addMessage(
            roomId = request.roomId,
            sender = request.sender,
            content = request.content,
            time = Clock.System.now(),
        )
    }

    override suspend fun pull(userId: UserId, roomId: RoomId): List<ChatMessage> {
        requireMember(userId, roomId)
        return rooms.messagesIn(roomId)
    }

    // Leaving between this check and the following read/write is equivalent to acting just before leaving.
    private suspend fun requireMember(userId: UserId, roomId: RoomId) {
        if (!memberships.isMember(userId, roomId)) throw NotInRoomException()
    }
}
