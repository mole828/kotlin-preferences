package preferences.backend

import io.github.smyrgeorge.sqlx4k.impl.coroutines.TransactionContext
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import preferences.modeling.MembershipService
import preferences.modeling.ChatService
import preferences.modeling.NotInRoomException
import preferences.modeling.SendMessageRequest
import preferences.modeling.types.RoomId
import preferences.modeling.types.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TransactionTest {
    private val user = UserId("alice")
    private val room = RoomId("lobby")

    @Test
    fun longLivedRepositoriesRequireCurrentTransactionOnEveryCall() = testApplication {
        withChatDatabase { db ->
            val memberships = SqlxMembershipRepository()
            val messages = SqlxChatRoomRepository()
            assertIs<IllegalStateException>(runCatching {
                memberships.addUserToRoom(user, room)
            }.exceptionOrNull())
            assertIs<IllegalStateException>(runCatching {
                messages.pullMessage(user, room, null)
            }.exceptionOrNull())

            TransactionContext.withCurrent(db) { memberships.addUserToRoom(user, room) }
            assertIs<IllegalStateException>(runCatching {
                memberships.deleteUserFromRoom(user, room)
            }.exceptionOrNull())
            TransactionContext.withCurrent(db) { assertTrue(messages.pullMessage(user, room, null).isEmpty()) }
            TransactionContext.withCurrent(db) { memberships.deleteUserFromRoom(user, room) }
            TransactionContext.withCurrent(db) {
                assertIs<NotInRoomException>(runCatching {
                    messages.pullMessage(user, room, null)
                }.exceptionOrNull())
            }
        }
    }

    @Test
    fun nestedServiceCallsReuseTransactionAcrossDispatcherSwitch() = testApplication {
        withChatDatabase { db ->
            val chat = ChatService(MembershipService(SqlxMembershipRepository()), SqlxChatRoomRepository())
            TransactionContext.withCurrent(db) {
                val outer = TransactionContext.current()
                chat.join(user, room)
                withContext(Dispatchers.Default) {
                    TransactionContext.withCurrent(db) { assertSame(outer, TransactionContext.current()) }
                    chat.send(SendMessageRequest(user, room, "hello"))
                }
            }
            assertEquals(listOf("alice 加入了群聊", "hello"), TransactionContext.withCurrent(db) { chat.pull(user, room) }.map { it.content })
        }
    }

    @Test
    fun outerFailureRollsBackNestedServiceWrites() = testApplication {
        withChatDatabase { db ->
            val chat = ChatService(MembershipService(SqlxMembershipRepository()), SqlxChatRoomRepository())
            val failure = runCatching {
                TransactionContext.withCurrent(db) {
                    chat.join(user, room)
                    error("outer failed")
                }
            }.exceptionOrNull()
            assertEquals("outer failed", failure?.message)
            assertIs<NotInRoomException>(runCatching { TransactionContext.withCurrent(db) { chat.pull(user, room) } }.exceptionOrNull())
            TransactionContext.withCurrent(db) { chat.join(user, room) }
            assertEquals(1, TransactionContext.withCurrent(db) { chat.pull(user, room) }.size)
        }
    }

    @Test
    fun caughtNestedFailureUsesSqlx4kCommitSemantics() = testApplication {
        withChatDatabase { db ->
            val chat = ChatService(MembershipService(SqlxMembershipRepository()), SqlxChatRoomRepository())
            TransactionContext.withCurrent(db) {
                chat.join(user, room)
                val failure = runCatching {
                    TransactionContext.withCurrent(db) { error("inner failed") }
                }.exceptionOrNull()
                assertEquals("inner failed", failure?.message)
                // The exception did not escape the outer block, so sqlx4k commits it.
            }
            assertEquals(listOf("alice 加入了群聊"), TransactionContext.withCurrent(db) { chat.pull(user, room) }.map { it.content })
        }
    }
}

internal suspend fun withChatDatabase(block: suspend (Driver) -> Unit) {
    val driver = openSqliteDatabase("sqlite::memory:", ConnectionPool.Options(maxConnections = 1))
    try {
        SqlxMembershipRepository().initialize(driver)
        SqlxChatRoomRepository().initialize(driver)
        block(driver)
    } finally {
        driver.close().getOrThrow()
    }
}
