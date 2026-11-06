package preferences.backend

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.coroutines.TransactionContext
import preferences.modeling.ChatRoomRepository
import preferences.modeling.types.ChatMessage
import preferences.modeling.types.MessageContent
import preferences.modeling.types.MessageSeq
import preferences.modeling.types.RoomId
import preferences.modeling.types.UserId
import kotlin.time.Instant

/** PostgreSQL-backed repository shared by the JVM and Kotlin/Native server targets. */
class SqlxChatRoomRepository(private val database: Driver) : ChatRoomRepository {
    suspend fun initialize() {
        val executor = database.currentExecutor()
        executor.execute(
            """
            CREATE TABLE IF NOT EXISTS chat_rooms (
                room_id TEXT PRIMARY KEY,
                last_seq BIGINT NOT NULL
            )
            """.trimIndent(),
        ).getOrThrow()
        executor.execute(
            """
            CREATE TABLE IF NOT EXISTS chat_messages (
                room_id TEXT NOT NULL,
                seq BIGINT NOT NULL,
                sender_id TEXT NOT NULL,
                content TEXT NOT NULL,
                time_seconds BIGINT NOT NULL,
                time_nanos INTEGER NOT NULL CHECK (time_nanos BETWEEN 0 AND 999999999),
                PRIMARY KEY (room_id, seq)
            )
            """.trimIndent(),
        ).getOrThrow()
    }

    /**
     * Takes the next seq from the room's counter row. The row stays locked until the transaction ends, so writers
     * of one room commit in seq order and a reader never sees seq 6 before seq 5 becomes visible.
     * Without this lock, an identity column could commit 11 before 10, and a reader resuming after 11 would miss 10.
     */
    override suspend fun addMessage(
        roomId: RoomId,
        sender: UserId,
        content: MessageContent,
        time: Instant,
    ): ChatMessage = TransactionContext.withCurrent(database) {
        val executor = database.currentExecutor()
        val seqRows = executor.fetchAll(
            Statement.create(
                """
                INSERT INTO chat_rooms(room_id, last_seq) VALUES (:roomId, 1)
                ON CONFLICT (room_id) DO UPDATE SET last_seq = chat_rooms.last_seq + 1
                RETURNING last_seq
                """.trimIndent(),
            ).bind("roomId", roomId.value),
        ).getOrThrow()
        val seq = MessageSeq(seqRows.first().get("last_seq").asString().toLong())

        executor.execute(
            Statement.create(
                """
                INSERT INTO chat_messages(room_id, seq, sender_id, content, time_seconds, time_nanos)
                VALUES (:roomId, :seq, :senderId, :content, :timeSeconds, :timeNanos)
                """.trimIndent(),
            )
                .bind("roomId", roomId.value)
                .bind("seq", seq.value)
                .bind("senderId", sender.value)
                .bind("content", content.value)
                .bind("timeSeconds", time.epochSeconds)
                .bind("timeNanos", time.nanosecondsOfSecond.toLong()),
        ).getOrThrow()
        executor.notifyRoomChanged(roomId)

        ChatMessage(roomId = roomId, seq = seq, sender = sender, content = content, time = time)
    }

    override suspend fun messagesAfter(roomId: RoomId, after: MessageSeq?): List<ChatMessage> {
        val statement = Statement.create(
            """
            SELECT room_id, seq, sender_id, content, time_seconds, time_nanos
            FROM chat_messages
            WHERE room_id = :roomId AND seq > :after
            ORDER BY seq
            """.trimIndent(),
        )
            .bind("roomId", roomId.value)
            .bind("after", after?.value ?: 0L)

        return database.currentExecutor().fetchAll(statement).getOrThrow().map { row -> row.toChatMessage() }
    }

    private fun ResultSet.Row.toChatMessage() = ChatMessage(
        roomId = RoomId(get("room_id").asString()),
        seq = MessageSeq(get("seq").asString().toLong()),
        sender = UserId(get("sender_id").asString()),
        content = MessageContent(get("content").asString()),
        time = Instant.fromEpochSeconds(
            get("time_seconds").asString().toLong(),
            get("time_nanos").asString().toLong(),
        ),
    )
}
