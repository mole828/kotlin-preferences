package preferences.backend

import preferences.modeling.Atomicity
import preferences.modeling.ChatRoomRepository
import preferences.modeling.MembershipRepository
import preferences.modeling.types.ChatMessage
import preferences.modeling.types.MessageContent
import preferences.modeling.types.MessageId
import preferences.modeling.types.RoomId
import preferences.modeling.types.UserId
import kotlin.time.Instant

/**
 * In-memory reference implementation of the repositories and [Atomicity]: the logic baseline that the
 * SQL implementations are compared against. State is immutable snapshots, so rolling back restores one.
 * Not thread-safe; for single-coroutine tests.
 *
 * @param rejectedContent a message content that fails to save, like the constraint added in the PostgreSQL test.
 */
class MemoryChatStore(private val rejectedContent: String? = null) {
    private var memberships = setOf<Pair<UserId, RoomId>>()
    private var messages = listOf<ChatMessage>()

    val membershipRepository = object : MembershipRepository {
        override suspend fun addUserToRoom(userId: UserId, roomId: RoomId): Boolean {
            if (userId to roomId in memberships) return false
            memberships = memberships + (userId to roomId)
            return true
        }

        override suspend fun deleteUserFromRoom(userId: UserId, roomId: RoomId) {
            memberships = memberships - (userId to roomId)
        }

        override suspend fun isMember(userId: UserId, roomId: RoomId): Boolean = userId to roomId in memberships
    }

    val chatRoomRepository = object : ChatRoomRepository {
        override suspend fun addMessage(
            roomId: RoomId,
            sender: UserId,
            content: MessageContent,
            time: Instant,
        ): ChatMessage {
            check(content.value != rejectedContent) { "Rejected message content: ${content.value}" }
            val message = ChatMessage(MessageId(messages.size + 1L), roomId, sender, content, time)
            messages = messages + message
            return message
        }

        override suspend fun messagesIn(roomId: RoomId): List<ChatMessage> =
            messages.filter { it.roomId == roomId }.sortedWith(compareBy({ it.time }, { it.id.value }))
    }

    /** Restores the state from before [Atomicity.atomic] when its block fails; nested calls restore their own snapshot. */
    val atomicity = object : Atomicity {
        override suspend fun <T> atomic(block: suspend () -> T): T {
            val savedMemberships = memberships
            val savedMessages = messages
            try {
                return block()
            } catch (e: Throwable) {
                memberships = savedMemberships
                messages = savedMessages
                throw e
            }
        }
    }
}
