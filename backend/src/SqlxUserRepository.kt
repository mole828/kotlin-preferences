package preferences.backend

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.Statement
import preferences.modeling.UserRepository
import preferences.modeling.types.UserId

class SqlxUserRepository(private val database: QueryExecutor) : UserRepository {
    suspend fun initialize() {
        database.currentExecutor().execute(
            """
            CREATE TABLE IF NOT EXISTS users (
                username TEXT PRIMARY KEY,
                password_hash TEXT NOT NULL
            )
            """.trimIndent(),
        ).getOrThrow()
    }

    override suspend fun addUser(username: UserId, passwordHash: String): Boolean =
        database.currentExecutor().execute(
            Statement.create(
                "INSERT INTO users(username, password_hash) VALUES (:username, :passwordHash) " +
                    "ON CONFLICT(username) DO NOTHING",
            )
                .bind("username", username.value)
                .bind("passwordHash", passwordHash),
        ).getOrThrow() > 0

    override suspend fun passwordHash(username: UserId): String? {
        val rows = database.currentExecutor().fetchAll(
            Statement.create("SELECT password_hash FROM users WHERE username = :username")
                .bind("username", username.value),
        ).getOrThrow()
        return rows.rows.firstOrNull()?.get("password_hash")?.asString()
    }
}
