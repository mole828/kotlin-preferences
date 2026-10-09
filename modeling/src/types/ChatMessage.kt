package preferences.modeling.types

import kotlin.time.Instant
import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val sender: UserId,
    val content: String,
    val time: Instant,
    val roomId: RoomId,
    val id: Long = 0,
)
