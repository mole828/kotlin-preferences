package preferences.backend

import io.github.smyrgeorge.sqlx4k.impl.coroutines.TransactionContext
import io.github.smyrgeorge.sqlx4k.postgres.PostgreSQL
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.coroutines.runBlocking
import preferences.modeling.ChatService
import preferences.modeling.MembershipService
import preferences.modeling.SendMessageRequest
import preferences.modeling.types.RoomId
import preferences.modeling.types.UserId
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatDatabaseTest {
    @Test
    fun sendsAndReadsMessagesInEmbeddedPostgres(): Unit = runBlocking {
        EmbeddedPostgres.start().use { postgres ->
            val database = PostgreSQL(
                url = "postgresql://localhost:${postgres.port}/postgres",
                username = "postgres",
                password = "postgres",
            )
            try {
                val memberships = SqlxMembershipRepository()
                val messages = SqlxChatRoomRepository()
                memberships.initialize(database)
                // The runtime uses SQLite; this test supplies PostgreSQL's identity column syntax.
                database.execute(
                    """
                    CREATE TABLE chat_messages (
                        id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                        room_id TEXT NOT NULL,
                        sender_id TEXT NOT NULL,
                        content TEXT NOT NULL,
                        time_seconds BIGINT NOT NULL,
                        time_nanos INTEGER NOT NULL
                    )
                    """.trimIndent(),
                ).getOrThrow()
                val chat = ChatService(MembershipService(memberships), messages)
                TransactionContext.withCurrent(database) {
                    val user = UserId("alice")
                    val room = RoomId("lobby")
                    chat.join(user, room)
                    chat.send(SendMessageRequest(user, room, "你好"))
                    assertEquals(listOf("alice 加入了群聊", "你好"), chat.pull(user, room).map { it.content })
                }
            } finally {
                database.close().getOrThrow()
            }
        }
    }
}
