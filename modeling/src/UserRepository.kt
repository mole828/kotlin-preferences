package preferences.modeling

import preferences.modeling.types.UserId

interface UserRepository {
    /** False when [username] already exists. */
    suspend fun addUser(username: UserId, passwordHash: String): Boolean

    suspend fun passwordHash(username: UserId): String?
}
