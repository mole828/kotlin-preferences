package preferences.modeling.types

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/**
 * A message's position within its room, starting at 1. Positions follow commit order, so a reader that has seen
 * every message up to a position can resume after it without missing a message committed later.
 */
@Serializable
@JvmInline
value class MessageSeq(val value: Long) : Comparable<MessageSeq> {
    init {
        require(value >= 1) { "消息序号从 1 开始" }
    }

    override fun compareTo(other: MessageSeq): Int = value.compareTo(other.value)
}
