package preferences.backend

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.impl.coroutines.TransactionContext
import preferences.modeling.Atomicity

class SqlxAtomicity(private val database: Driver) : Atomicity {
    override suspend fun <T> atomic(block: suspend () -> T): T =
        TransactionContext.withCurrent(database) { block() }
}
