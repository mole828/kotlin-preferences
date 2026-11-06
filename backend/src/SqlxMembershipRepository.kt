package preferences.backend

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.Statement
import preferences.modeling.MembershipRepository
import preferences.modeling.types.RoomId
import preferences.modeling.types.UserId

class SqlxMembershipRepository(private val database: QueryExecutor) : MembershipRepository {
    suspend fun initialize() {
        database.currentExecutor().execute(
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
        val executor = database.currentExecutor()
        val added = executor.execute(
            Statement.create(
                "INSERT INTO room_memberships(room_id, user_id) VALUES (:roomId, :userId) " +
                    "ON CONFLICT(room_id, user_id) DO NOTHING",
            )
                .bind("roomId", roomId.value)
                .bind("userId", userId.value),
        ).getOrThrow() > 0
        if (added) executor.notifyRoomChanged(roomId)
        return added
    }

    // Readers of the room re-check their membership when signalled, so a user who left stops receiving messages.
    override suspend fun deleteUserFromRoom(userId: UserId, roomId: RoomId) {
        val executor = database.currentExecutor()
        executor.execute(
            Statement.create(
                "DELETE FROM room_memberships WHERE room_id = :roomId AND user_id = :userId",
            )
                .bind("roomId", roomId.value)
                .bind("userId", userId.value),
        ).getOrThrow()
        executor.notifyRoomChanged(roomId)
    }

    override suspend fun isMember(userId: UserId, roomId: RoomId): Boolean {
        val rows = database.currentExecutor().fetchAll(
            Statement.create(
                "SELECT 1 FROM room_memberships WHERE room_id = :roomId AND user_id = :userId LIMIT 1",
            )
                .bind("roomId", roomId.value)
                .bind("userId", userId.value),
        ).getOrThrow()
        return rows.rows.isNotEmpty()
    }
}
