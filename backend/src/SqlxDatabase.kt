package preferences.backend

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Transaction
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import preferences.modeling.Database
import preferences.modeling.MembershipService
import preferences.modeling.TransactionScope

class SqlxDatabase(private val driver: Driver) : Database {
    suspend fun initialize() {
        SqlxMembershipRepository(driver).initialize()
        SqlxChatRoomRepository(driver).initialize()
    }

    override suspend fun <T> transaction(block: suspend TransactionScope.() -> T): T {
        val tx = driver.begin().getOrThrow()
        try {
            val scope = object : TransactionScope {
                override val memberships = MembershipService(SqlxMembershipRepository(tx))
                override val messages = SqlxChatRoomRepository(tx)
            }
            val result = block(scope)
            currentCoroutineContext().ensureActive()
            tx.commit().getOrThrow()
            return result
        } catch (failure: Throwable) {
            // Cleanup must still run when the caller's coroutine was cancelled.
            withContext(NonCancellable) {
                if (tx.status == Transaction.Status.Open) {
                    tx.rollback().onFailure { failure.addSuppressed(it) }
                }
            }
            throw failure
        }
    }
}
