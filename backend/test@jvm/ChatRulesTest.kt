package preferences.backend

import io.github.smyrgeorge.sqlx4k.postgres.PostgreSQL
import io.ktor.server.testing.testApplication
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.coroutines.runBlocking
import preferences.client.KtorChatClient
import preferences.modeling.ChatClient
import preferences.modeling.ChatError
import preferences.modeling.ChatException
import preferences.modeling.ChatService
import preferences.modeling.SendMessageRequest
import preferences.modeling.types.MessageContent
import preferences.modeling.types.RoomId
import preferences.modeling.types.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith

/** Saving this welcome message fails in every environment, to exercise the rollback of a join. */
private const val REJECTED_WELCOME = "blocked 加入了群聊"

/**
 * Runs the same chat rules against the in-memory reference, against PostgreSQL and across HTTP.
 * A rule that passes in memory but fails on PostgreSQL points at a difference in the SQL implementation;
 * one that fails only across HTTP points at the transport, such as a business failure that does not come back
 * as the same [ChatError].
 */
class ChatRulesTest {
    @Test
    fun inMemory(): Unit = runBlocking {
        val store = MemoryChatStore(rejectedContent = REJECTED_WELCOME)
        verifyChatRules(ChatService(store.membershipRepository, store.chatRoomRepository, store.atomicity))
    }

    /** Serves the in-memory reference, so a failure here is about the transport rather than the database. */
    @Test
    fun overHttp() = testApplication {
        val store = MemoryChatStore(rejectedContent = REJECTED_WELCOME)
        application { backendModule(ChatService(store.membershipRepository, store.chatRoomRepository, store.atomicity)) }
        verifyChatRules(KtorChatClient(client, "http://localhost"))
    }

    @Test
    fun onPostgres(): Unit = runBlocking {
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
                database.execute(
                    "ALTER TABLE chat_messages ADD CONSTRAINT rejected_welcome CHECK (content <> '$REJECTED_WELCOME')",
                ).getOrThrow()
                verifyChatRules(ChatService(memberships, messages, SqlxAtomicity(database)))
            } finally {
                database.close().getOrThrow()
            }
        }
    }
}

/** The chat rules every environment must satisfy, observed only through [ChatClient]. */
private suspend fun verifyChatRules(chat: ChatClient) {
    val alice = UserId("alice")
    val room = RoomId("lobby")

    // Joining twice announces the user only once.
    chat.join(alice, room)
    chat.join(alice, room)
    chat.send(SendMessageRequest(alice, room, MessageContent("你好")))
    assertEquals(listOf("alice 加入了群聊", "你好"), chat.pull(alice, room).map { it.content.value })

    // Joining and announcing are atomic: when the welcome message fails, the membership is rolled back.
    val blocked = UserId("blocked")
    assertFails { chat.join(blocked, room) }
    assertNotInRoom { chat.pull(blocked, room) }

    // After leaving, the user can neither send nor read.
    chat.leave(alice, room)
    assertNotInRoom { chat.send(SendMessageRequest(alice, room, MessageContent("还在吗"))) }
    assertNotInRoom { chat.pull(alice, room) }
}

private suspend fun assertNotInRoom(action: suspend () -> Unit) {
    val failure = assertFailsWith<ChatException> { action() }
    assertEquals(ChatError.NotInRoom, failure.error)
}
