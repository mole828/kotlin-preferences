package preferences.modeling.types

import kotlin.time.Instant
import kotlinx.serialization.Serializable

/** A message already saved by the repository, so it always carries its database ID. */
@Serializable
data class ChatMessage(
    val id: MessageId,
    val roomId: RoomId,
    val sender: UserId,
    val content: MessageContent,
    val time: Instant,
)
