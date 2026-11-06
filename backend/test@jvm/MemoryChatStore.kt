package preferences.backend

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import preferences.modeling.Atomicity
import preferences.modeling.ChatRoomRepository
import preferences.modeling.MembershipRepository
import preferences.modeling.RoomSignals
import preferences.modeling.types.ChatMessage
import preferences.modeling.types.MessageContent
import preferences.modeling.types.MessageSeq
import preferences.modeling.types.RoomId
import preferences.modeling.types.UserId
import kotlin.concurrent.Volatile
import kotlin.time.Instant

/**
 * In-memory reference implementation of the repositories, [RoomSignals] and [Atomicity]: the logic baseline that
 * the PostgreSQL implementations are compared against. State is immutable snapshots, so rolling back restores one.
 * Readers may run concurrently with one writer at a time, as when a test streams over HTTP while it sends.
 *
 * @param rejectedContent a message content that fails to save, like the constraint added in the PostgreSQL test.
 */
class MemoryChatStore(private val rejectedContent: String? = null) {
    @Volatile private var memberships = setOf<Pair<UserId, RoomId>>()
    @Volatile private var messages = listOf<ChatMessage>()

    private val versions = MutableStateFlow(mapOf<RoomId, Long>())

    // Like PostgreSQL's NOTIFY, a change inside atomic is signalled only after the outermost block succeeds.
    private var depth = 0
    private var pendingSignals = setOf<RoomId>()

    private fun signal(roomId: RoomId) {
        if (depth > 0) {
            pendingSignals = pendingSignals + roomId
        } else {
            versions.update { it + (roomId to (it[roomId] ?: 0) + 1) }
        }
    }

    val membershipRepository = object : MembershipRepository {
        override suspend fun addUserToRoom(userId: UserId, roomId: RoomId): Boolean {
            if (userId to roomId in memberships) return false
            memberships = memberships + (userId to roomId)
            signal(roomId)
            return true
        }

        override suspend fun deleteUserFromRoom(userId: UserId, roomId: RoomId) {
            memberships = memberships - (userId to roomId)
            signal(roomId)
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
            val seq = MessageSeq(messages.count { it.roomId == roomId } + 1L)
            val message = ChatMessage(roomId, seq, sender, content, time)
            messages = messages + message
            signal(roomId)
            return message
        }

        override suspend fun messagesAfter(roomId: RoomId, after: MessageSeq?): List<ChatMessage> =
            messages.filter { it.roomId == roomId && (after == null || it.seq > after) }
    }

    val roomSignals = object : RoomSignals {
        override fun changes(roomId: RoomId): Flow<Unit> = versions.map { it[roomId] }.distinctUntilChanged().map { }
    }

    /** Restores the state from before [Atomicity.atomic] when its block fails; nested calls restore their own snapshot. */
    val atomicity = object : Atomicity {
        override suspend fun <T> atomic(block: suspend () -> T): T {
            val savedMemberships = memberships
            val savedMessages = messages
            val savedSignals = pendingSignals
            depth++
            val result = try {
                block()
            } catch (e: Throwable) {
                memberships = savedMemberships
                messages = savedMessages
                pendingSignals = savedSignals
                throw e
            } finally {
                depth--
            }
            if (depth == 0) {
                val committed = pendingSignals
                pendingSignals = emptySet()
                committed.forEach(::signal)
            }
            return result
        }
    }
}
