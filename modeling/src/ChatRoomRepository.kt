package preferences.modeling

import preferences.modeling.types.ChatMessage
import preferences.modeling.types.MessageContent
import preferences.modeling.types.MessageSeq
import preferences.modeling.types.RoomId
import preferences.modeling.types.UserId
import kotlin.time.Instant

interface ChatRoomRepository {
    /** Assigns the room's next [MessageSeq]; seqs become visible to readers in seq order. */
    suspend fun addMessage(roomId: RoomId, sender: UserId, content: MessageContent, time: Instant): ChatMessage

    /** Messages of [roomId] after [after] in seq order, or all of them when [after] is null. */
    suspend fun messagesAfter(roomId: RoomId, after: MessageSeq?): List<ChatMessage>
}
