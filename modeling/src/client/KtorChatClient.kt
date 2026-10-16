package preferences.modeling.client

import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import preferences.modeling.ChatClient
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

    private suspend fun membership(action: String, userId: UserId, roomId: RoomId) {
        httpClient.post("$endpoint/$action") {
            expectSuccess = true
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(RoomMembershipRequest(userId, roomId)))
        }.bodyAsText()
    }

    override suspend fun send(request: SendMessageRequest): ChatMessage {
        val response = httpClient.post("$endpoint/messages") {
            expectSuccess = true
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(request))
        }
        return json.decodeFromString(response.bodyAsText())
    }

    override suspend fun pull(userId: UserId, roomId: RoomId): List<ChatMessage> {
        val response = httpClient.get("$endpoint/messages") {
            expectSuccess = true
            parameter("userId", userId.value)
            parameter("roomId", roomId.value)
        }
        return json.decodeFromString(response.bodyAsText())
    }
}
