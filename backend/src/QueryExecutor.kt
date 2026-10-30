package preferences.backend

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.impl.coroutines.TransactionContext

/** Use the current transaction when present, otherwise this executor. */
internal suspend fun QueryExecutor.currentExecutor(): QueryExecutor =
    TransactionContext.currentOrNull() ?: this
