package preferences.backend

import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import preferences.modeling.ChatClient
import preferences.modeling.ChatError
import preferences.modeling.ChatException
import preferences.modeling.SendMessageRequest
import preferences.modeling.client.KtorChatClient
import preferences.modeling.types.ChatMessage
import preferences.modeling.types.MessageContent
import preferences.modeling.types.RoomId
import preferences.modeling.types.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Checks that business failures cross HTTP as the same ChatError; business rules are covered by ChatDatabaseTest. */
class ChatApiTest {
    @Test
    fun restoresBusinessErrorsThroughHttp() = testApplication {
        application { backendModule(NonMemberChat) }
        val chat = KtorChatClient(client, "http://localhost")
        val user = UserId("alice")
        val room = RoomId("lobby")

        val sendError = assertFailsWith<ChatException> {
            chat.send(SendMessageRequest(user, room, MessageContent("你好")))
        }
        assertEquals(ChatError.NotInRoom, sendError.error)
        assertEquals(ChatError.NotInRoom, assertFailsWith<ChatException> { chat.pull(user, room) }.error)

        // Responses that are not a ChatError keep Ktor's exception.
        val missingRoute = KtorChatClient(client, "http://localhost/missing")
        val error = assertFailsWith<ClientRequestException> { missingRoute.pull(user, room) }
        assertEquals(HttpStatusCode.NotFound, error.response.status)
    }
}

/** Behaves like ChatService for a user who has not joined any room. */
private object NonMemberChat : ChatClient {
    override suspend fun join(userId: UserId, roomId: RoomId) = Unit
    override suspend fun leave(userId: UserId, roomId: RoomId) = Unit
    override suspend fun send(request: SendMessageRequest): ChatMessage = throw ChatException(ChatError.NotInRoom)
    override suspend fun pull(userId: UserId, roomId: RoomId): List<ChatMessage> =
        throw ChatException(ChatError.NotInRoom)
}
