package preferences.backend

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.Statement
import preferences.modeling.MembershipRepository
import preferences.modeling.types.RoomId
import preferences.modeling.types.UserId

class SqlxMembershipRepository(private val database: QueryExecutor) : MembershipRepository {
    suspend fun initialize() {
        database.execute(
            """
            CREATE TABLE IF NOT EXISTS room_memberships (
                room_id TEXT NOT NULL,
                user_id TEXT NOT NULL,
                PRIMARY KEY (room_id, user_id)
            )
            """.trimIndent(),
        ).getOrThrow()
    }

    override suspend fun addUserToRoom(userId: UserId, roomId: RoomId): Boolean {
        return database.execute(
            Statement.create(
                "INSERT INTO room_memberships(room_id, user_id) VALUES (:roomId, :userId) " +
                    "ON CONFLICT(room_id, user_id) DO NOTHING",
            )
                .bind("roomId", roomId.value)
                .bind("userId", userId.value),
        ).getOrThrow() > 0
    }

    override suspend fun deleteUserFromRoom(userId: UserId, roomId: RoomId) {
        database.execute(
            Statement.create(
                "DELETE FROM room_memberships WHERE room_id = :roomId AND user_id = :userId",
            )
                .bind("roomId", roomId.value)
                .bind("userId", userId.value),
        ).getOrThrow()
    }
}
