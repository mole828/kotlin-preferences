package preferences.modeling

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import preferences.modeling.client.KtorChatClient
import preferences.modeling.types.ChatMessage
import preferences.modeling.types.RoomId
import preferences.modeling.types.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class KtorChatClientTest {
    private val user = UserId("小林 & alice")
    private val room = RoomId("开发/大厅")
    private val timestamp = Instant.parse("2026-10-09T08:00:00.123456789Z")
    private val jsonHeaders = headersOf("Content-Type", "application/json")

    @Test
    fun joinsAndLeavesUsingSharedMembershipDto() = runTest {
        val paths = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            paths.add(request.url.encodedPath)
            assertEquals("POST", request.method.value)
            assertEquals(
                RoomMembershipRequest(user, room),
                Json.decodeFromString<RoomMembershipRequest>((request.body as TextContent).text),
            )
            respond("", HttpStatusCode.NoContent)
        })
        try {
            val client: ChatClient = KtorChatClient(http, "https://chat.example/")
            client.join(user, room)
            client.leave(user, room)
            assertEquals(listOf("/api/chat/join", "/api/chat/leave"), paths)
        } finally {
            http.close()
        }
    }

    @Test
    fun sendsAndPullsMessagesWithoutLosingTypedIdsOrTimestamp() = runTest {
        val request = SendMessageRequest(user, room, "你好\n'\"")
        val message = ChatMessage(user, request.content, timestamp, room, 7)
        val http = HttpClient(MockEngine { outgoing ->
            assertEquals("/api/chat/messages", outgoing.url.encodedPath)
            if (outgoing.method.value == "POST") {
                assertEquals(request, Json.decodeFromString<SendMessageRequest>((outgoing.body as TextContent).text))
                respond(Json.encodeToString(message), HttpStatusCode.Created, jsonHeaders)
            } else {
                assertEquals(user.value, outgoing.url.parameters["userId"])
                assertEquals(room.value, outgoing.url.parameters["roomId"])
                assertEquals(timestamp.toString(), outgoing.url.parameters["after"])
                respond(Json.encodeToString(listOf(message)), HttpStatusCode.OK, jsonHeaders)
            }
        })
        try {
            val client: ChatClient = KtorChatClient(http, "https://chat.example")
            assertEquals(message, client.send(request))
            assertEquals(listOf(message), client.pull(user, room, timestamp))
        } finally {
            http.close()
        }
    }

    @Test
    fun translatesServerErrorsEvenWithExpectSuccessEnabled() = runTest {
        val http = HttpClient(MockEngine {
            respond(Json.encodeToString(ChatApiError("请先加入房间")), HttpStatusCode.Forbidden, jsonHeaders)
        }) { expectSuccess = true }
        try {
            val client: ChatClient = KtorChatClient(http, "https://chat.example")
            val error = assertFailsWith<ChatClientException> { client.pull(user, room) }
            assertEquals(403, error.statusCode)
            assertEquals("请先加入房间", error.message)
        } finally {
            http.close()
        }
    }

    @Test
    fun handlesNonJsonProxyFailures() = runTest {
        val http = HttpClient(MockEngine { respond("Bad Gateway", HttpStatusCode.BadGateway) })
        try {
            val client = KtorChatClient(http, "https://chat.example")
            val error = assertFailsWith<ChatClientException> { client.join(user, room) }
            assertEquals(502, error.statusCode)
            assertEquals("聊天请求失败（HTTP 502）", error.message)
        } finally {
            http.close()
        }
    }
}
