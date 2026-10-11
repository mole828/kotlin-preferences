package preferences.backend

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import preferences.modeling.MembershipRepository
import preferences.modeling.MembershipService
import preferences.modeling.ChatRoomRepository
import preferences.modeling.NotInRoomException
import preferences.modeling.ChatService
import preferences.modeling.RoomMembershipRequest
import preferences.modeling.SendMessageRequest
import preferences.modeling.client.KtorChatClient
import preferences.modeling.projectProfile
import preferences.modeling.types.ChatMessage
import preferences.modeling.types.RoomId
import preferences.modeling.types.UserId

class ServerTest {
    @Test
    fun joiningWritesMembershipAndOneMessageUntilLeaving() = testApplication {
        val database = openSqliteDatabase("sqlite::memory:", ConnectionPool.Options(maxConnections = 1))
        try {
            val memberships = SqlxMembershipRepository(database)
            val messages = SqlxChatRoomRepository(database)
            val chat = ChatService(messages, MembershipService(memberships))
            val user = UserId("alice")
            val room = RoomId("lobby")

            chat.join(user, room)
            chat.join(user, room)
            val joined = chat.pull(user, room)
            assertEquals(listOf("alice 加入了群聊"), joined.map(ChatMessage::content))
            assertEquals(user, joined.single().sender)
            assertEquals(room, joined.single().roomId)

            chat.leave(user, room)
            assertFailsAs<NotInRoomException> { chat.pull(user, room) }
            chat.join(user, room)
            assertEquals(2, chat.pull(user, room).size)
        } finally {
            database.close().getOrThrow()
        }
    }

    // Baseline for transaction exploration: the two writes are not yet atomic.
    @Test
    fun failedJoinMessageCurrentlyLeavesMembershipBehind() = testApplication {
        val database = openSqliteDatabase("sqlite::memory:", ConnectionPool.Options(maxConnections = 1))
        try {
            val memberships = SqlxMembershipRepository(database)
            val chat = ChatService(ExplodingRepository(), MembershipService(memberships))
            val user = UserId("alice")
            val room = RoomId("lobby")

            assertFailsAs<IllegalStateException> { chat.join(user, room) }
            assertFalse(memberships.addUserToRoom(user, room))
            assertTrue(SqlxChatRoomRepository(database).pullMessage(user, room, null).isEmpty())
        } finally {
            database.close().getOrThrow()
        }
    }

    @Test
    fun servesProjectProfile() = testApplication {
        application { backendModule(ChatService(ExplodingRepository(), MembershipService(ExplodingRepository()))) }
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("${projectProfile.name}: ${projectProfile.backend}", response.bodyAsText())
    }

    @Test
    fun healthCheck() = testApplication {
        application { backendModule(ChatService(ExplodingRepository(), MembershipService(ExplodingRepository()))) }
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("OK", response.bodyAsText())
    }

    @Test
    fun sqliteRepositoryEnforcesMembershipAndInclusiveStableRoomScopedPulls() = testApplication {
        val database = openSqliteDatabase(
            url = "sqlite::memory:",
            options = ConnectionPool.Options(maxConnections = 1),
        )
        try {
            val repository = SqlxChatRoomRepository(database)
            val memberships = SqlxMembershipRepository(database)
            memberships.initialize()
            val user = UserId("alice")
            val room = RoomId("room-a")
            val otherRoom = RoomId("room-b")
            val boundary = Instant.parse("2026-10-09T01:02:03.123456789Z")

            assertFailsAs<NotInRoomException> {
                repository.pullMessage(UserId("stranger"), room, null)
            }
            assertFailsAs<NotInRoomException> {
                repository.addMessage(message(user, room, "blocked", boundary))
            }

            memberships.addUserToRoom(user, room)
            memberships.addUserToRoom(user, room)
            memberships.addUserToRoom(user, otherRoom)
            val first = repository.addMessage(message(user, room, "first", boundary))
            val second = repository.addMessage(message(user, room, "second", boundary))
            repository.addMessage(
                message(user, room, "later", Instant.fromEpochSeconds(boundary.epochSeconds, 123456790)),
            )
            repository.addMessage(message(user, otherRoom, "other room", boundary))

            val pulled = repository.pullMessage(user, room, boundary)
            assertEquals(listOf(first.id, second.id), pulled.take(2).map(ChatMessage::id))
            assertEquals(listOf("first", "second", "later"), pulled.map(ChatMessage::content))
            assertTrue(pulled.all { it.roomId == room })
            assertEquals(boundary, pulled.first().time)

            memberships.deleteUserFromRoom(user, room)
            memberships.deleteUserFromRoom(user, room)
            assertFailsAs<NotInRoomException> {
                repository.addMessage(message(user, room, "after leave", boundary))
            }
            assertFailsAs<NotInRoomException> {
                repository.pullMessage(user, room, null)
            }
            memberships.addUserToRoom(user, room)
            assertEquals(3, repository.pullMessage(user, room, null).size)
        } finally {
            database.close().getOrThrow()
        }
    }

    @Test
    fun sqliteRepositoryPersistsMessagesAcrossReopen() = testApplication {
        val fileName = "chat-restart-${Random.nextLong().toString().replace("-", "")}.db"
        val url = "sqlite:///tmp/$fileName"
        val user = UserId("persisted-user")
        val room = RoomId("persistent-room")
        val persisted = message(user, room, "kept after close", Instant.parse("2026-10-09T01:02:03.000000001Z"))

        val firstDatabase = openSqliteDatabase(url, ConnectionPool.Options())
        val firstRepository = SqlxChatRoomRepository(firstDatabase)
        val memberships = SqlxMembershipRepository(firstDatabase)
        val inserted = try {
            memberships.addUserToRoom(user, room)
            firstRepository.addMessage(persisted)
        } finally {
            firstDatabase.close().getOrThrow()
        }

        val reopenedDatabase = openSqliteDatabase(url, ConnectionPool.Options())
        val reopenedRepository = SqlxChatRoomRepository(reopenedDatabase)
        try {
            val restored = reopenedRepository.pullMessage(user, room, persisted.time)
            assertEquals(listOf(inserted), restored)
        } finally {
            reopenedDatabase.close().getOrThrow()
        }
    }

    @Test
    fun sharedClientMatchesBackendApiAndMapsErrors() = testApplication {
        val repository = InMemoryRepository()
        application { backendModule(ChatService(repository, MembershipService(repository))) }
        val chatClient = KtorChatClient(client, "http://localhost")

        val joinStatus = client.post("/api/chat/join") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(RoomMembershipRequest(UserId("alice"), RoomId("room-a"))))
        }
        assertEquals(HttpStatusCode.NoContent, joinStatus.status)

        chatClient.join(UserId("alice"), RoomId("room-a"))

        val beforeSend = Clock.System.now()
        val saved = chatClient.send(SendMessageRequest(UserId("alice"), RoomId("room-a"), "hello"))
        val afterSend = Clock.System.now()
        assertEquals("hello", saved.content)
        assertTrue(saved.time >= beforeSend && saved.time <= afterSend)
        assertTrue(saved.id > 0)

        val rawCreated = client.post("/api/chat/messages") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(SendMessageRequest(UserId("alice"), RoomId("room-a"), "status probe")))
        }
        assertEquals(HttpStatusCode.Created, rawCreated.status)
        val rawCreatedMessage = Json.decodeFromString<ChatMessage>(rawCreated.bodyAsText())
        assertEquals("status probe", rawCreatedMessage.content)
        assertTrue(rawCreatedMessage.id > saved.id)

        val pulled = chatClient.pull(UserId("alice"), RoomId("room-a"), saved.time)
        val expectedAfterSaved = listOf(saved, rawCreatedMessage)
            .filter { it.time >= saved.time }
            .sortedWith(compareBy<ChatMessage>({ it.time }, { it.id }))
        assertEquals(expectedAfterSaved, pulled)
        val rawPull = client.get("/api/chat/messages?userId=alice&roomId=room-a&after=${saved.time}")
        assertEquals(HttpStatusCode.OK, rawPull.status)

        val missingQuery = client.get("/api/chat/messages?userId=alice")
        assertEquals(HttpStatusCode.BadRequest, missingQuery.status)
        val missingBodyField = client.post("/api/chat/join") {
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"alice"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, missingBodyField.status)
        val invalidTime = client.get("/api/chat/messages?userId=alice&roomId=room-a&after=not-a-time")
        assertEquals(HttpStatusCode.BadRequest, invalidTime.status)
        val blankMessage = client.post("/api/chat/messages") {
            contentType(ContentType.Application.Json)
            setBody("""{"sender":"alice","roomId":"room-a","content":"   "}""")
        }
        assertEquals(HttpStatusCode.BadRequest, blankMessage.status)

        chatClient.leave(UserId("alice"), RoomId("room-a"))
        val leaveStatus = client.post("/api/chat/leave") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(RoomMembershipRequest(UserId("alice"), RoomId("room-a"))))
        }
        assertEquals(HttpStatusCode.NoContent, leaveStatus.status)
        val deniedPull = client.get("/api/chat/messages?userId=alice&roomId=room-a")
        assertEquals(HttpStatusCode.Forbidden, deniedPull.status)
        val deniedSend = client.post("/api/chat/messages") {
            contentType(ContentType.Application.Json)
            setBody("""{"sender":"alice","roomId":"room-a","content":"denied"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, deniedSend.status)
    }

    @Test
    fun unexpectedRepositoryErrorsReturnGenericServerError() = testApplication {
        application { backendModule(ChatService(ExplodingRepository(), MembershipService(ExplodingRepository()))) }
        val response = client.post("/api/chat/join") {
            contentType(ContentType.Application.Json)
            setBody("""{"userId":"alice","roomId":"room-a"}""")
        }

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertFalse(response.bodyAsText().contains("database secret"))
    }

    private fun message(user: UserId, room: RoomId, content: String, time: Instant) =
        ChatMessage(sender = user, content = content, time = time, roomId = room)

    private suspend inline fun <reified T : Throwable> assertFailsAs(noinline block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertIs<T>(failure)
    }

    private class ExplodingRepository : ChatRoomRepository, MembershipRepository {
        override suspend fun addUserToRoom(userId: UserId, roomId: RoomId): Boolean {
            error("database secret")
        }

        override suspend fun deleteUserFromRoom(userId: UserId, roomId: RoomId) {
            error("database secret")
        }

        override suspend fun addMessage(chatMessage: ChatMessage): ChatMessage = error("database secret")

        override suspend fun pullMessage(userId: UserId, roomId: RoomId, after: Instant?): List<ChatMessage> =
            error("database secret")
    }

    private class InMemoryRepository : ChatRoomRepository, MembershipRepository {
        private val members = mutableSetOf<Pair<UserId, RoomId>>()
        private val messages = mutableListOf<ChatMessage>()
        private var nextId = 1L

        override suspend fun addUserToRoom(userId: UserId, roomId: RoomId): Boolean {
            return members.add(userId to roomId)
        }

        override suspend fun deleteUserFromRoom(userId: UserId, roomId: RoomId) {
            members -= userId to roomId
        }

        override suspend fun addMessage(chatMessage: ChatMessage): ChatMessage {
            if ((chatMessage.sender to chatMessage.roomId) !in members) throw NotInRoomException()
            return chatMessage.copy(id = nextId++).also(messages::add)
        }

        override suspend fun pullMessage(userId: UserId, roomId: RoomId, after: Instant?): List<ChatMessage> {
            if ((userId to roomId) !in members) throw NotInRoomException()
            return messages
                .filter { it.roomId == roomId && (after == null || it.time >= after) }
                .sortedWith(compareBy<ChatMessage>({ it.time }, { it.id }))
        }
    }
}
