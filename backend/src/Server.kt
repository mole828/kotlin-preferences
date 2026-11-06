package preferences.backend

import io.github.smyrgeorge.sqlx4k.postgres.PostgreSQL
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.routing.route
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import preferences.modeling.ChatClient
import preferences.modeling.ChatError
import preferences.modeling.ChatException
import preferences.modeling.ChatService
import preferences.modeling.RoomMembershipRequest
import preferences.modeling.SendMessageRequest
import preferences.modeling.MessageStreamEvent
import preferences.modeling.projectProfile
import preferences.modeling.types.MessageSeq
import preferences.modeling.types.RoomId
import preferences.modeling.types.UserId

fun Application.backendModule(chatClient: ChatClient) {
    install(ContentNegotiation) { json() }
    install(SSE)
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            when (cause) {
                is CancellationException -> throw cause
                // The declared type ChatError makes the body polymorphic JSON with a "type" field.
                is ChatException -> call.respond<ChatError>(statusOf(cause.error), cause.error)
                is BadRequestException, is ContentTransformationException, is IllegalArgumentException ->
                    call.respondText("请求参数无效", status = HttpStatusCode.BadRequest)
                else -> call.respondText("服务器内部错误", status = HttpStatusCode.InternalServerError)
            }
        }
    }

    routing {
        get("/") {
            call.respondText("${projectProfile.name}: ${projectProfile.backend}")
        }
        get("/health") { call.respondText("OK") }
        route("/api/chat") {
            post("/join") {
                val request = call.receive<RoomMembershipRequest>()
                chatClient.join(request.userId, request.roomId)
                call.respond(HttpStatusCode.NoContent)
            }
            post("/leave") {
                val request = call.receive<RoomMembershipRequest>()
                chatClient.leave(request.userId, request.roomId)
                call.respond(HttpStatusCode.NoContent)
            }
            post("/messages") {
                val request = call.receive<SendMessageRequest>()
                val message = chatClient.send(request)
                call.respond(HttpStatusCode.Created, message)
            }
            // Invalid parameters close the stream without an event: the response has already started.
            sse("/messages") {
                val userId = UserId(call.requiredQueryParameter("userId"))
                val roomId = RoomId(call.requiredQueryParameter("roomId"))
                val after = call.request.queryParameters["after"]?.let { MessageSeq(it.toLong()) }
                try {
                    chatClient.messages(userId, roomId, after).collect { message ->
                        send(event(MessageStreamEvent.Message(message)))
                    }
                } catch (e: ChatException) {
                    send(event(MessageStreamEvent.Failure(e.error)))
                }
            }
        }
    }
}

/** The declared type MessageStreamEvent makes the data polymorphic JSON with a "type" field. */
private fun event(event: MessageStreamEvent) = ServerSentEvent(data = Json.encodeToString<MessageStreamEvent>(event))

/** Maps failure categories, so a new failure within an existing category needs no change here. */
private fun statusOf(error: ChatError): HttpStatusCode = when (error) {
    is ChatError.NotPermitted -> HttpStatusCode.Forbidden
}

private fun ApplicationCall.requiredQueryParameter(name: String): String =
    request.queryParameters[name] ?: throw BadRequestException("Missing query parameter: $name")

/** Defaults match the PostgreSQL service in compose.yaml. */
fun runBackend(
    host: String = "127.0.0.1",
    port: Int = 8080,
    databaseUrl: String = "postgresql://localhost:5432/postgres",
    databaseUser: String = "postgres",
    databasePassword: String = "postgres",
): Unit = runBlocking {
    val database = PostgreSQL(url = databaseUrl, username = databaseUser, password = databasePassword)
    try {
        val memberships = SqlxMembershipRepository(database)
        val messages = SqlxChatRoomRepository(database)
        memberships.initialize()
        messages.initialize()
        val chat = ChatService(
            memberships = memberships,
            rooms = messages,
            signals = PgRoomSignals.listen(database),
            atomicity = SqlxAtomicity(database),
        )
        embeddedServer(CIO, host = host, port = port) {
            backendModule(chat)
        }.start(wait = true)
    } finally {
        database.close().getOrThrow()
    }
}
