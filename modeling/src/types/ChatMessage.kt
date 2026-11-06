package preferences.modeling.types

import kotlin.time.Instant
import kotlinx.serialization.Serializable

/** A message already saved by the repository; [roomId] and [seq] identify it. */
@Serializable
data class ChatMessage(
    val roomId: RoomId,
    val seq: MessageSeq,
    val sender: UserId,
    val content: MessageContent,
    val time: Instant,
)
