package preferences.modeling

import preferences.modeling.types.RoomId
import preferences.modeling.types.UserId

interface MembershipRepository {
    // True only when a new membership was inserted.
    suspend fun addUserToRoom(userId: UserId, roomId: RoomId): Boolean
    suspend fun deleteUserFromRoom(userId: UserId, roomId: RoomId)
}
