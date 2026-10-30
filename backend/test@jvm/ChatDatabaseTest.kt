package preferences.backend

import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.impl.coroutines.TransactionContext
import io.github.smyrgeorge.sqlx4k.postgres.PostgreSQL
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.coroutines.runBlocking
import preferences.modeling.ChatService
import preferences.modeling.MembershipService
import preferences.modeling.NotInRoomException
import preferences.modeling.SendMessageRequest
import preferences.modeling.types.RoomId
import preferences.modeling.types.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

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
                val memberships = SqlxMembershipRepository(database)
                val messages = SqlxChatRoomRepository(database)
                memberships.initialize()
                // The runtime uses SQLite; this test supplies PostgreSQL's identity column syntax.
                database.execute(
                    """
                    CREATE TABLE chat_messages (
                        id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                        room_id TEXT NOT NULL,
                        sender_id TEXT NOT NULL,
                        content TEXT NOT NULL CHECK (content <> 'blocked 加入了群聊'),
                        time_seconds BIGINT NOT NULL,
                        time_nanos INTEGER NOT NULL
                    )
                    """.trimIndent(),
                ).getOrThrow()
                val chat = ChatService(MembershipService(memberships), messages)
                val user = UserId("alice")
                val room = RoomId("lobby")
                TransactionContext.withCurrent(database) {
                    assertSame(this, database.currentExecutor())
                    chat.join(user, room)
                    chat.join(user, room)
                    chat.send(SendMessageRequest(user, room, "你好"))
                    assertEquals(listOf("alice 加入了群聊", "你好"), chat.pull(user, room).map { it.content })
                }

                // A database constraint rejects the welcome message after membership insertion.
                assertFailsWith<SQLError> {
                    TransactionContext.withCurrent(database) { chat.join(UserId("blocked"), room) }
                }
                val blockedMemberships = database.fetchAll(
                    "SELECT COUNT(*) AS count FROM room_memberships WHERE user_id = 'blocked'",
                ).getOrThrow()
                assertEquals(0L, blockedMemberships.first().get("count").asString().toLong())
                TransactionContext.withCurrent(database) {
                    assertEquals(listOf("alice 加入了群聊", "你好"), chat.pull(user, room).map { it.content })
                }

                // With no transaction, all repository operations use the default executor.
                assertSame(database, database.currentExecutor())
                val bob = UserId("bob")
                chat.join(bob, room)
                chat.send(SendMessageRequest(bob, room, "默认执行器"))
                assertEquals("默认执行器", chat.pull(bob, room).last().content)
                chat.leave(bob, room)
                assertFailsWith<NotInRoomException> {
                    chat.send(SendMessageRequest(bob, room, "已离开房间"))
                }
                assertFailsWith<NotInRoomException> { chat.pull(bob, room) }
            } finally {
                database.close().getOrThrow()
            }
        }
    }
}
