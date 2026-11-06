package preferences.modeling

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import preferences.modeling.types.ChatMessage
import preferences.modeling.types.MessageContent
import preferences.modeling.types.MessageSeq
import preferences.modeling.types.RoomId
import preferences.modeling.types.UserId
import kotlin.time.Clock

/** The chat rules. The acting user is an explicit parameter; [actingAs] fixes it for a signed-in caller. */
class ChatService(
    private val memberships: MembershipRepository,
    private val rooms: ChatRoomRepository,
    private val signals: RoomSignals,
    private val atomicity: Atomicity,
) {
    /** Uses this service as [user], once the caller has established who the user is. */
    fun actingAs(user: UserId): ChatClient = ActingAs(user)

    private inner class ActingAs(override val user: UserId) : ChatClient {
        override suspend fun join(roomId: RoomId) = join(user, roomId)
        override suspend fun leave(roomId: RoomId) = leave(user, roomId)
        override suspend fun send(roomId: RoomId, content: MessageContent) = send(user, roomId, content)
        override fun messages(roomId: RoomId, after: MessageSeq?) = messages(user, roomId, after)
    }

    suspend fun join(userId: UserId, roomId: RoomId) {
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

    suspend fun leave(userId: UserId, roomId: RoomId) {
        memberships.deleteUserFromRoom(userId, roomId)
    }

    suspend fun send(sender: UserId, roomId: RoomId, content: MessageContent): ChatMessage {
        requireMember(sender, roomId)
        return rooms.addMessage(
            roomId = roomId,
            sender = sender,
            content = content,
            time = Clock.System.now(),
        )
    }

    // Each signal is answered the same way: check the membership, then read what came after the last message sent.
    // The first signal arrives on collection, so the reader subscribes before reading and cannot miss a change.
    fun messages(userId: UserId, roomId: RoomId, after: MessageSeq?): Flow<ChatMessage> = flow {
        var last = after
        signals.changes(roomId).collect {
            requireMember(userId, roomId)
            for (message in rooms.messagesAfter(roomId, last)) {
                emit(message)
                last = message.seq
            }
        }
    }

    // Leaving between this check and the following read/write is equivalent to acting just before leaving.
    private suspend fun requireMember(userId: UserId, roomId: RoomId) {
        if (!memberships.isMember(userId, roomId)) throw ChatException(ChatError.NotInRoom)
    }
}
