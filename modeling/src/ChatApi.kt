package preferences.modeling

import kotlinx.serialization.Serializable
import preferences.modeling.types.RoomId
import preferences.modeling.types.UserId

@Serializable
data class RoomMembershipRequest(val userId: UserId, val roomId: RoomId)

@Serializable
data class SendMessageRequest(val sender: UserId, val roomId: RoomId, val content: String)

@Serializable
data class ChatApiError(val message: String)

class NotInRoomException : IllegalStateException("请先加入房间")
