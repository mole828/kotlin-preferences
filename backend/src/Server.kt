package preferences.backend

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.impl.coroutines.TransactionContext
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import preferences.modeling.ChatApiError
import preferences.modeling.ChatClient
import preferences.modeling.MembershipService
import preferences.modeling.ChatService
import preferences.modeling.NotInRoomException
import preferences.modeling.RoomMembershipRequest
import preferences.modeling.SendMessageRequest
import preferences.modeling.projectProfile
import preferences.modeling.types.RoomId
import preferences.modeling.types.UserId

fun Application.backendModule(database: Driver, chatClient: ChatClient) {
    install(ContentNegotiation) { json() }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            val (status, message) = when (cause) {
                is CancellationException -> throw cause
                is NotInRoomException -> HttpStatusCode.Forbidden to "请先加入房间"
                is BadRequestException, is ContentTransformationException, is IllegalArgumentException ->
                    HttpStatusCode.BadRequest to "请求参数无效"
                else -> HttpStatusCode.InternalServerError to "服务器内部错误"
            }
            call.respond(status, ChatApiError(message))
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
                TransactionContext.withCurrent(database) { chatClient.join(request.userId, request.roomId) }
                call.respond(HttpStatusCode.NoContent)
            }
            post("/leave") {
                val request = call.receive<RoomMembershipRequest>()
                TransactionContext.withCurrent(database) { chatClient.leave(request.userId, request.roomId) }
                call.respond(HttpStatusCode.NoContent)
            }
            post("/messages") {
                val request = call.receive<SendMessageRequest>()
                val message = TransactionContext.withCurrent(database) { chatClient.send(request) }
                call.respond(HttpStatusCode.Created, message)
            }
            get("/messages") {
                val userId = UserId(call.requiredQueryParameter("userId"))
                val roomId = RoomId(call.requiredQueryParameter("roomId"))
                val messages = TransactionContext.withCurrent(database) { chatClient.pull(userId, roomId) }
                call.respond(messages)
            }
        }
    }
}

private fun ApplicationCall.requiredQueryParameter(name: String): String =
    request.queryParameters[name] ?: throw BadRequestException("Missing query parameter: $name")

fun runBackend(
    host: String = "127.0.0.1",
    port: Int = 8080,
    databaseUrl: String = DEFAULT_CHAT_DATABASE_URL,
): Unit = runBlocking {
    val database = openSqliteDatabase(databaseUrl, ConnectionPool.Options())
    try {
        val memberships = SqlxMembershipRepository(database)
        val messages = SqlxChatRoomRepository(database)
        memberships.initialize()
        messages.initialize()
        val chat = ChatService(
            memberships = MembershipService(memberships),
            rooms = messages,
            atomicity = SqlxAtomicity(database),
        )
        embeddedServer(CIO, host = host, port = port) {
            backendModule(database, chat)
        }.start(wait = true)
    } finally {
        database.close().getOrThrow()
    }
}
