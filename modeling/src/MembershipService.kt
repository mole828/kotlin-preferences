package preferences.modeling

import preferences.modeling.types.RoomId
import preferences.modeling.types.UserId

class MembershipService(
    private val membershipRepository: MembershipRepository,
) {
    suspend fun join(userId: UserId, roomId: RoomId): Boolean {
        validateIds(userId, roomId)
        return membershipRepository.addUserToRoom(userId, roomId)
    }

    suspend fun leave(userId: UserId, roomId: RoomId) {
        validateIds(userId, roomId)
        membershipRepository.deleteUserFromRoom(userId, roomId)
    }
}
