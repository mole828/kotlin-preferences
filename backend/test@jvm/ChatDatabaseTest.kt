package preferences.backend

import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.impl.coroutines.TransactionContext
import io.github.smyrgeorge.sqlx4k.postgres.PostgreSQL
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.coroutines.runBlocking
import preferences.modeling.ChatService
import preferences.modeling.ChatError
import preferences.modeling.ChatException
import preferences.modeling.SendMessageRequest
import preferences.modeling.types.MessageContent
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
                messages.initialize()
                // Test-only constraint that makes the welcome message for "blocked" fail.
                database.execute(
                    "ALTER TABLE chat_messages ADD CONSTRAINT block_welcome " +
                        "CHECK (content <> 'blocked 加入了群聊')",
                ).getOrThrow()
                val chat = ChatService(
                    memberships = memberships,
                    rooms = messages,
                    atomicity = SqlxAtomicity(database),
                )
                val user = UserId("alice")
                val room = RoomId("lobby")
                TransactionContext.withCurrent(database) {
                    assertSame(this, database.currentExecutor())
                    chat.join(user, room)
                    chat.join(user, room)
                    chat.send(SendMessageRequest(user, room, MessageContent("你好")))
                    assertEquals(listOf("alice 加入了群聊", "你好"), chat.pull(user, room).map { it.content.value })
                }

                // A direct join must roll back membership when the welcome message fails.
                assertFailsWith<SQLError> {
                    chat.join(UserId("blocked"), room)
                }
                val blockedMemberships = database.fetchAll(
                    "SELECT COUNT(*) AS count FROM room_memberships WHERE user_id = 'blocked'",
                ).getOrThrow()
                assertEquals(0L, blockedMemberships.first().get("count").asString().toLong())
                TransactionContext.withCurrent(database) {
                    assertEquals(listOf("alice 加入了群聊", "你好"), chat.pull(user, room).map { it.content.value })
                }

                // A join must participate in the caller's transaction, not commit independently.
                assertFailsWith<IllegalStateException> {
                    TransactionContext.withCurrent(database) {
                        chat.join(user, RoomId("outer-rollback"))
                        error("Abort after joining")
                    }
                }
                for (table in listOf("room_memberships", "chat_messages")) {
                    val rows = database.fetchAll(
                        "SELECT COUNT(*) AS count FROM $table WHERE room_id = 'outer-rollback'",
                    ).getOrThrow()
                    assertEquals(0L, rows.first().get("count").asString().toLong())
                }

                // Outside join's transaction, single-statement operations use the default executor.
                assertSame(database, database.currentExecutor())
                val bob = UserId("bob")
                chat.join(bob, room)
                chat.join(bob, room)
                assertSame(database, database.currentExecutor())
                chat.send(SendMessageRequest(bob, room, MessageContent("默认执行器")))
                assertEquals("默认执行器", chat.pull(bob, room).last().content.value)
                chat.leave(bob, room)
                val sendError = assertFailsWith<ChatException> {
                    chat.send(SendMessageRequest(bob, room, MessageContent("已离开房间")))
                }
                assertEquals(ChatError.NotInRoom, sendError.error)
                assertEquals(ChatError.NotInRoom, assertFailsWith<ChatException> { chat.pull(bob, room) }.error)
            } finally {
                database.close().getOrThrow()
            }
        }
    }
}
