package preferences.backend

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import preferences.modeling.ChatRoomRepository
import preferences.modeling.NotInRoomException
import preferences.modeling.types.ChatMessage
import preferences.modeling.types.RoomId
import preferences.modeling.types.UserId
import kotlin.time.Instant

const val DEFAULT_CHAT_DATABASE_URL = "sqlite://chat.db"

/** SQLite-backed repository shared by the JVM and Kotlin/Native server targets. */
class SqlxChatRoomRepository(
    private val database: Driver,
) : ChatRoomRepository {
    private val initializationMutex = Mutex()
    private var initialized = false

    suspend fun initialize() {
        initializationMutex.withLock {
            if (initialized) return

            database.execute(
                """
                CREATE TABLE IF NOT EXISTS chat_messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    room_id TEXT NOT NULL,
                    sender_id TEXT NOT NULL,
                    content TEXT NOT NULL,
                    time_seconds INTEGER NOT NULL,
                    time_nanos INTEGER NOT NULL CHECK (time_nanos BETWEEN 0 AND 999999999)
                )
                """.trimIndent(),
            ).getOrThrow()
            database.execute(
                "CREATE INDEX IF NOT EXISTS chat_messages_room_time_id " +
                    "ON chat_messages(room_id, time_seconds, time_nanos, id)",
            ).getOrThrow()
            initialized = true
        }
    }

    override suspend fun addMessage(chatMessage: ChatMessage): ChatMessage {
        initialize()
        val insertedRows = database.fetchAll(
            Statement.create(
                """
                INSERT INTO chat_messages(room_id, sender_id, content, time_seconds, time_nanos)
                SELECT :roomId, :senderId, :content, :timeSeconds, :timeNanos
                WHERE EXISTS (
                    SELECT 1 FROM room_memberships
                    WHERE room_id = :roomId AND user_id = :senderId
                )
                RETURNING id
                """.trimIndent(),
            )
                .bind("roomId", chatMessage.roomId.value)
                .bind("senderId", chatMessage.sender.value)
                .bind("content", chatMessage.content)
                .bind("timeSeconds", chatMessage.time.epochSeconds)
                .bind("timeNanos", chatMessage.time.nanosecondsOfSecond.toLong()),
        ).getOrThrow()

        val id = insertedRows.firstOrNull()?.get("id")?.asString()?.toLong()
            ?: throw NotInRoomException()
        return chatMessage.copy(id = id)
    }

    override suspend fun pullMessage(
        userId: UserId,
        roomId: RoomId,
        after: Instant?,
    ): List<ChatMessage> {
        initialize()
        val membership = database.fetchAll(
            Statement.create(
                "SELECT 1 FROM room_memberships WHERE room_id = :roomId AND user_id = :userId LIMIT 1",
            )
                .bind("roomId", roomId.value)
                .bind("userId", userId.value),
        ).getOrThrow()
        if (membership.rows.isEmpty()) throw NotInRoomException()

        val statement = if (after == null) {
            Statement.create(
                """
                SELECT id, room_id, sender_id, content, time_seconds, time_nanos
                FROM chat_messages
                WHERE room_id = :roomId
                ORDER BY time_seconds, time_nanos, id
                """.trimIndent(),
            ).bind("roomId", roomId.value)
        } else {
            Statement.create(
                """
                SELECT id, room_id, sender_id, content, time_seconds, time_nanos
                FROM chat_messages
                WHERE room_id = :roomId
                  AND (time_seconds > :afterSeconds OR
                       (time_seconds = :afterSeconds AND time_nanos >= :afterNanos))
                ORDER BY time_seconds, time_nanos, id
                """.trimIndent(),
            )
                .bind("roomId", roomId.value)
                .bind("afterSeconds", after.epochSeconds)
                .bind("afterNanos", after.nanosecondsOfSecond.toLong())
        }

        return database.fetchAll(statement).getOrThrow().map { row ->
            ChatMessage(
                sender = UserId(row.get("sender_id").asString()),
                content = row.get("content").asString(),
                time = Instant.fromEpochSeconds(
                    row.get("time_seconds").asString().toLong(),
                    row.get("time_nanos").asString().toLong(),
                ),
                roomId = RoomId(row.get("room_id").asString()),
                id = row.get("id").asString().toLong(),
            )
        }
    }
}
