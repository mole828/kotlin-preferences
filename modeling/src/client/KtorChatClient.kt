package preferences.modeling.client

import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import preferences.modeling.ChatApiError
import preferences.modeling.ChatClient
import preferences.modeling.ChatClientException
import preferences.modeling.RoomMembershipRequest
import preferences.modeling.SendMessageRequest
import preferences.modeling.types.ChatMessage
import preferences.modeling.types.RoomId
import preferences.modeling.types.UserId
import kotlin.time.Instant

/** The caller selects the platform engine and owns the supplied HttpClient. */
class KtorChatClient(
    private val httpClient: HttpClient,
    baseUrl: String,
) : ChatClient {
    private val endpoint = baseUrl.trimEnd('/') + "/api/chat"
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun join(userId: UserId, roomId: RoomId) {
        membership("join", userId, roomId)
    }

    override suspend fun leave(userId: UserId, roomId: RoomId) {
        membership("leave", userId, roomId)
    }

    private suspend fun membership(action: String, userId: UserId, roomId: RoomId) {
        httpClient.post("$endpoint/$action") {
            expectSuccess = false
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(json.encodeToString(RoomMembershipRequest(userId, roomId)))
        }.checkedText()
    }

    override suspend fun send(request: SendMessageRequest): ChatMessage {
        val response = httpClient.post("$endpoint/messages") {
            expectSuccess = false
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(json.encodeToString(request))
        }
        return json.decodeFromString(response.checkedText())
    }

    override suspend fun pull(userId: UserId, roomId: RoomId, after: Instant?): List<ChatMessage> {
        val response = httpClient.get("$endpoint/messages") {
            expectSuccess = false
            accept(ContentType.Application.Json)
            parameter("userId", userId.value)
            parameter("roomId", roomId.value)
            after?.let { parameter("after", it.toString()) }
        }
        return json.decodeFromString(response.checkedText())
    }

    private suspend fun HttpResponse.checkedText(): String {
        val text = bodyAsText()
        if (status.value !in 200..299) {
            val message = runCatching { json.decodeFromString<ChatApiError>(text).message }
                .getOrNull()?.takeIf { it.isNotBlank() }
                ?: "聊天请求失败（HTTP ${status.value}）"
            throw ChatClientException(status.value, message)
        }
        return text
    }
}
