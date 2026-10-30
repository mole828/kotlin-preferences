package preferences.modeling

/** Execute an operation atomically, participating in an existing transaction when present. */
interface Atomicity {
    suspend fun <T> atomic(block: suspend () -> T): T
}
