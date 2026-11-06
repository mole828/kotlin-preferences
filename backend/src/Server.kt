package preferences.backend

import io.github.smyrgeorge.sqlx4k.postgres.PostgreSQL
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import preferences.modeling.AccountService
import preferences.modeling.ChatError
import preferences.modeling.ChatException
import preferences.modeling.ChatService
import preferences.modeling.Credentials
import preferences.modeling.MessageStreamEvent
import preferences.modeling.RoomRequest
import preferences.modeling.SendMessageRequest
import preferences.modeling.SignedIn
import preferences.modeling.projectProfile
import preferences.modeling.types.MessageSeq
import preferences.modeling.types.RoomId
import preferences.modeling.types.UserId

fun Application.backendModule(chat: ChatService, accounts: AccountService, tokens: JwtTokens) {
    install(ContentNegotiation) { json() }
    install(SSE)
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            when (cause) {
                is CancellationException -> throw cause
                is ChatException -> {
                    if (cause.error is ChatError.NotAuthenticated) call.response.header(HttpHeaders.WWWAuthenticate, "Bearer")
                    // The declared type ChatError makes the body polymorphic JSON with a "type" field.
                    call.respond<ChatError>(statusOf(cause.error), cause.error)
                }
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
        route("/api/auth") {
            post("/register") {
                val credentials = call.receive<Credentials>()
                val user = accounts.register(credentials.username, credentials.password)
                call.respond(HttpStatusCode.Created, SignedIn(user, tokens.issue(user)))
            }
            post("/login") {
                val credentials = call.receive<Credentials>()
                val user = accounts.login(credentials.username, credentials.password)
                call.respond(SignedIn(user, tokens.issue(user)))
            }
        }
        route("/api/chat") {
            install(BearerAuthentication) { this.tokens = tokens }
            post("/join") {
                chat.join(call.signedInUser, call.receive<RoomRequest>().roomId)
                call.respond(HttpStatusCode.NoContent)
            }
            post("/leave") {
                chat.leave(call.signedInUser, call.receive<RoomRequest>().roomId)
                call.respond(HttpStatusCode.NoContent)
            }
            post("/messages") {
                val request = call.receive<SendMessageRequest>()
                val message = chat.send(call.signedInUser, request.roomId, request.content)
                call.respond(HttpStatusCode.Created, message)
            }
            // Authentication runs before the stream starts, so an invalid token still gets a 401 response.
            // Invalid parameters close the stream without an event: the response has already started.
            sse("/messages") {
                val roomId = RoomId(call.requiredQueryParameter("roomId"))
                val after = call.request.queryParameters["after"]?.let { MessageSeq(it.toLong()) }
                try {
                    chat.messages(call.signedInUser, roomId, after).collect { message ->
                        send(event(MessageStreamEvent.Message(message)))
                    }
                } catch (e: ChatException) {
                    send(event(MessageStreamEvent.Failure(e.error)))
                }
            }
        }
    }
}

private val SignedInUserKey = AttributeKey<UserId>("SignedInUser")

/** The user of the call's bearer token; available in routes under [BearerAuthentication]. */
private val ApplicationCall.signedInUser: UserId get() = attributes[SignedInUserKey]

private class BearerAuthenticationConfig {
    lateinit var tokens: JwtTokens
}

/** Rejects calls without a valid bearer token with [ChatError.SessionExpired], before the route handler runs. */
private val BearerAuthentication = createRouteScopedPlugin("BearerAuthentication", ::BearerAuthenticationConfig) {
    val tokens = pluginConfig.tokens
    onCall { call ->
        val token = call.request.headers[HttpHeaders.Authorization]
            ?.takeIf { it.startsWith("Bearer ") }
            ?.removePrefix("Bearer ")
        val user = token?.let(tokens::verify) ?: throw ChatException(ChatError.SessionExpired)
        call.attributes.put(SignedInUserKey, user)
    }
}

/** The declared type MessageStreamEvent makes the data polymorphic JSON with a "type" field. */
private fun event(event: MessageStreamEvent) = ServerSentEvent(data = Json.encodeToString<MessageStreamEvent>(event))

/** Maps failure categories, so a new failure within an existing category needs no change here. */
private fun statusOf(error: ChatError): HttpStatusCode = when (error) {
    is ChatError.NotPermitted -> HttpStatusCode.Forbidden
    is ChatError.Conflict -> HttpStatusCode.Conflict
    is ChatError.NotAuthenticated -> HttpStatusCode.Unauthorized
}

private fun ApplicationCall.requiredQueryParameter(name: String): String =
    request.queryParameters[name] ?: throw BadRequestException("Missing query parameter: $name")

/** Used when no secret is configured; anyone who reads this source can sign tokens with it. */
private const val DEVELOPMENT_JWT_SECRET = "kotlin-preferences-development-secret-do-not-deploy"

/**
 * Database defaults match the PostgreSQL service in compose.yaml.
 * [jwtSecret] signs sign-in tokens; every instance must share it. Without it a development secret is used.
 */
fun runBackend(
    host: String = "127.0.0.1",
    port: Int = 8080,
    databaseUrl: String = "postgresql://localhost:5432/postgres",
    databaseUser: String = "postgres",
    databasePassword: String = "postgres",
    jwtSecret: String? = null,
): Unit = runBlocking {
    if (jwtSecret == null) println("警告：未配置 CHAT_JWT_SECRET，使用开发用签名密钥，不能用于部署")
    val tokens = JwtTokens((jwtSecret ?: DEVELOPMENT_JWT_SECRET).encodeToByteArray())
    val database = PostgreSQL(url = databaseUrl, username = databaseUser, password = databasePassword)
    try {
        val users = SqlxUserRepository(database)
        val memberships = SqlxMembershipRepository(database)
        val messages = SqlxChatRoomRepository(database)
        users.initialize()
        memberships.initialize()
        messages.initialize()
        val chat = ChatService(
            memberships = memberships,
            rooms = messages,
            signals = PgRoomSignals.listen(database),
            atomicity = SqlxAtomicity(database),
        )
        val accounts = AccountService(users, Pbkdf2PasswordHasher())
        embeddedServer(CIO, host = host, port = port) {
            backendModule(chat, accounts, tokens)
        }.start(wait = true)
    } finally {
        database.close().getOrThrow()
    }
}
