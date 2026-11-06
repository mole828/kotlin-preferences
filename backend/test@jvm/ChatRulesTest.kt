package preferences.backend

import io.github.smyrgeorge.sqlx4k.postgres.PostgreSQL
import io.ktor.client.HttpClient
import io.ktor.server.engine.embeddedServer
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.server.cio.CIO as ServerCIO
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import preferences.client.KtorChatClient
import preferences.modeling.ChatClient
import preferences.modeling.ChatError
import preferences.modeling.ChatException
import preferences.modeling.ChatService
import preferences.modeling.SendMessageRequest
import preferences.modeling.types.ChatMessage
import preferences.modeling.types.MessageContent
import preferences.modeling.types.MessageSeq
import preferences.modeling.types.RoomId
import preferences.modeling.types.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

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
        verifyChatRules(store.chatService())
    }

    /**
     * Serves the in-memory reference, so a failure here is about the transport rather than the database.
     * A real server on a free port: Ktor's test engine buffers response bodies, so a stream would never arrive.
     */
    @Test
    fun overHttp(): Unit = runBlocking {
        val store = MemoryChatStore(rejectedContent = REJECTED_WELCOME)
        val server = embeddedServer(ServerCIO, port = 0) { backendModule(store.chatService()) }.startSuspend()
        val port = server.engine.resolvedConnectors().first().port
        val httpClient = HttpClient(ClientCIO)
        try {
            verifyChatRules(KtorChatClient(httpClient, "http://127.0.0.1:$port"))
        } finally {
            httpClient.close()
            server.stopSuspend()
        }
    }

    /**
     * Two server instances share one database, as in a deployment with several instances: everything is written
     * through one and read through the other, so readers are woken only by PostgreSQL's notifications.
     */
    @Test
    fun onPostgres(): Unit = runBlocking {
        EmbeddedPostgres.start().use { postgres ->
            val databases = List(2) {
                PostgreSQL(
                    url = "postgresql://localhost:${postgres.port}/postgres",
                    username = "postgres",
                    password = "postgres",
                )
            }
            try {
                val (writer, reader) = databases.map { chatServiceOn(it) }
                databases.first().execute(
                    "ALTER TABLE chat_messages ADD CONSTRAINT rejected_welcome CHECK (content <> '$REJECTED_WELCOME')",
                ).getOrThrow()
                verifyChatRules(chat = writer, reader = reader)
            } finally {
                databases.forEach { it.close().getOrThrow() }
            }
        }
    }
}

/** Starts a server instance's chat service the way runBackend does. */
private suspend fun chatServiceOn(database: PostgreSQL): ChatService {
    val memberships = SqlxMembershipRepository(database)
    val messages = SqlxChatRoomRepository(database)
    memberships.initialize()
    messages.initialize()
    return ChatService(memberships, messages, PgRoomSignals.listen(database), SqlxAtomicity(database))
}

private fun MemoryChatStore.chatService() =
    ChatService(membershipRepository, chatRoomRepository, roomSignals, atomicity)

/**
 * The chat rules every environment must satisfy, observed only through [ChatClient].
 * Changes go through [chat] and messages are read through [reader], which may be another server instance.
 */
private suspend fun verifyChatRules(chat: ChatClient, reader: ChatClient = chat) = coroutineScope {
    val alice = UserId("alice")
    val room = RoomId("lobby")

    // Joining twice announces the user only once. A reader receives the history, then each new message.
    chat.join(alice, room)
    chat.join(alice, room)
    val aliceReader = Reader(this, reader.messages(alice, room))
    assertEquals("alice 加入了群聊", aliceReader.next())
    chat.send(SendMessageRequest(alice, room, MessageContent("你好")))
    assertEquals("你好", aliceReader.next())

    // Joining and announcing are atomic: when the welcome message fails, the membership is rolled back
    // and the welcome message never reaches a reader.
    val blocked = UserId("blocked")
    assertFails { chat.join(blocked, room) }
    assertNotInRoom { reader.messages(blocked, room).first() }
    chat.send(SendMessageRequest(alice, room, MessageContent("之后")))
    assertEquals("之后", aliceReader.next())

    // A reader resumes after the last message it received.
    val resumed = reader.messages(alice, room, after = MessageSeq(1)).take(2).toList()
    assertEquals(listOf("你好", "之后"), resumed.map { it.content.value })

    // Leaving ends the reader's stream; afterwards the user can neither send nor read.
    chat.leave(alice, room)
    assertNotInRoom { aliceReader.next() }
    assertNotInRoom { chat.send(SendMessageRequest(alice, room, MessageContent("还在吗"))) }
    assertNotInRoom { reader.messages(alice, room).first() }
}

/** Collects [messages] in the background, so the test can act while it reads. */
private class Reader(scope: CoroutineScope, messages: Flow<ChatMessage>) {
    private val received = Channel<ChatMessage>(Channel.UNLIMITED)

    init {
        scope.launch {
            try {
                messages.collect { received.send(it) }
            } catch (e: ChatException) {
                received.close(e)
            }
        }
    }

    /** The content of the next message; rethrows the failure that ended the stream. */
    suspend fun next(): String = withTimeout(5.seconds) { received.receive().content.value }
}

private suspend fun assertNotInRoom(action: suspend () -> Unit) {
    val failure = assertFailsWith<ChatException> { action() }
    assertEquals(ChatError.NotInRoom, failure.error)
}
