package preferences.client

import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import preferences.modeling.ChatClient
import preferences.modeling.ChatError
import preferences.modeling.ChatException
import preferences.modeling.RoomMembershipRequest
import preferences.modeling.SendMessageRequest
import preferences.modeling.types.ChatMessage
import preferences.modeling.types.RoomId
import preferences.modeling.types.UserId

/** The caller selects the platform engine and owns the supplied HttpClient. */
class KtorChatClient(
    private val httpClient: HttpClient,
    baseUrl: String,
) : ChatClient {
    private val endpoint = baseUrl.trimEnd('/') + "/api/chat"
    private val json = Json

    override suspend fun join(userId: UserId, roomId: RoomId) {
        membership("join", userId, roomId)
    }

    override suspend fun leave(userId: UserId, roomId: RoomId) {
        membership("leave", userId, roomId)
    }

    private suspend fun membership(action: String, userId: UserId, roomId: RoomId): Unit = restoringBusinessErrors {
        httpClient.post("$endpoint/$action") {
            expectSuccess = true
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(RoomMembershipRequest(userId, roomId)))
        }.bodyAsText()
    }

    override suspend fun send(request: SendMessageRequest): ChatMessage = restoringBusinessErrors {
        val response = httpClient.post("$endpoint/messages") {
            expectSuccess = true
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(request))
        }
        json.decodeFromString(response.bodyAsText())
    }

    override suspend fun pull(userId: UserId, roomId: RoomId): List<ChatMessage> = restoringBusinessErrors {
        val response = httpClient.get("$endpoint/messages") {
            expectSuccess = true
            parameter("userId", userId.value)
            parameter("roomId", roomId.value)
        }
        json.decodeFromString(response.bodyAsText())
    }

    /**
     * Rethrows business failures reported by the server as [ChatException],
     * so callers handle them the same way as when calling ChatService directly.
     * Other HTTP failures keep Ktor's exceptions.
     */
    private suspend fun <T> restoringBusinessErrors(request: suspend () -> T): T =
        try {
            request()
        } catch (e: ResponseException) {
            throw e.chatError()?.let(::ChatException) ?: e
        }

    /** Returns null when the body is not a ChatError, such as a text body for 400/500 or a 404 from unmatched routing. */
    private suspend fun ResponseException.chatError(): ChatError? =
        try {
            json.decodeFromString<ChatError>(response.bodyAsText())
        } catch (_: SerializationException) {
            null
        }
}
