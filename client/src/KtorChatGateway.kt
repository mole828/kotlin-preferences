package preferences.client

import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.SSEClientException
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import preferences.modeling.ChatClient
import preferences.modeling.ChatError
import preferences.modeling.ChatException
import preferences.modeling.ChatGateway
import preferences.modeling.Credentials
import preferences.modeling.MessageStreamEvent
import preferences.modeling.RoomRequest
import preferences.modeling.SendMessageRequest
import preferences.modeling.SignedIn
import preferences.modeling.types.ChatMessage
import preferences.modeling.types.MessageContent
import preferences.modeling.types.MessageSeq
import preferences.modeling.types.Password
import preferences.modeling.types.RoomId
import preferences.modeling.types.UserId

private val json = Json

/** The caller selects the platform engine and owns the supplied HttpClient; this gateway adds SSE to a copy of it. */
class KtorChatGateway(httpClient: HttpClient, baseUrl: String) : ChatGateway {
    private val httpClient = httpClient.config { install(SSE) }
    private val apiUrl = baseUrl.trimEnd('/') + "/api"

    override suspend fun register(username: UserId, password: Password): ChatClient = signIn("register", username, password)

    override suspend fun login(username: UserId, password: Password): ChatClient = signIn("login", username, password)

    private suspend fun signIn(action: String, username: UserId, password: Password): ChatClient {
        val signedIn = restoringBusinessErrors {
            val response = httpClient.post("$apiUrl/auth/$action") { jsonBody(Credentials(username, password)) }
            json.decodeFromString<SignedIn>(response.bodyAsText())
        }
        return KtorChatClient(httpClient, "$apiUrl/chat", signedIn)
    }
}

/** Sends the chat requests of a signed-in user with its bearer token. Obtained from [KtorChatGateway]. */
private class KtorChatClient(
    private val httpClient: HttpClient,
    private val endpoint: String,
    signedIn: SignedIn,
) : ChatClient {
    override val user = signedIn.user
    private val token = signedIn.token

    override suspend fun join(roomId: RoomId) {
        membership("join", roomId)
    }

    override suspend fun leave(roomId: RoomId) {
        membership("leave", roomId)
    }

    private suspend fun membership(action: String, roomId: RoomId): Unit = restoringBusinessErrors {
        httpClient.post("$endpoint/$action") {
            bearerAuth(token)
            jsonBody(RoomRequest(roomId))
        }.bodyAsText()
    }

    override suspend fun send(roomId: RoomId, content: MessageContent): ChatMessage = restoringBusinessErrors {
        val response = httpClient.post("$endpoint/messages") {
            bearerAuth(token)
            jsonBody(SendMessageRequest(roomId, content))
        }
        json.decodeFromString(response.bodyAsText())
    }

    override fun messages(roomId: RoomId, after: MessageSeq?): Flow<ChatMessage> = channelFlow {
        var failure: ChatError? = null
        try {
            httpClient.sse("$endpoint/messages", request = {
                bearerAuth(token)
                parameter("roomId", roomId.value)
                after?.let { parameter("after", it.value) }
            }) {
                incoming.collect { event ->
                    when (val decoded = json.decodeFromString<MessageStreamEvent>(checkNotNull(event.data))) {
                        is MessageStreamEvent.Message -> send(decoded.message)
                        is MessageStreamEvent.Failure -> failure = decoded.error // The server closes the stream next.
                    }
                }
            }
        } catch (e: SSEClientException) {
            // A response rejected before the stream started, such as 401 for an expired sign-in.
            throw e.response?.chatError()?.let(::ChatException) ?: e
        }
        // Thrown outside the sse block, which would wrap it in SSEClientException.
        failure?.let { throw ChatException(it) }
    }
}

private inline fun <reified T> HttpRequestBuilder.jsonBody(body: T) {
    expectSuccess = true
    contentType(ContentType.Application.Json)
    setBody(json.encodeToString(body))
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
        throw e.response.chatError()?.let(::ChatException) ?: e
    }

/** Returns null when the body is not a ChatError, such as a text body for 400/500 or a 404 from unmatched routing. */
private suspend fun HttpResponse.chatError(): ChatError? =
    try {
        json.decodeFromString<ChatError>(bodyAsText())
    } catch (_: SerializationException) {
        null
    }
