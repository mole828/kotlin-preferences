package preferences.modeling

import kotlinx.serialization.json.Json
import preferences.modeling.types.ChatMessage
import preferences.modeling.types.RoomId
import preferences.modeling.types.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class ChatContractTest {
    @Test
    fun membershipUsesPlainStringIdsOnTheWire() {
        val request = RoomMembershipRequest(UserId("小林"), RoomId("公共大厅"))
        val json = """{"userId":"小林","roomId":"公共大厅"}"""
        assertEquals(json, Json.encodeToString(request))
        assertEquals(request, Json.decodeFromString<RoomMembershipRequest>(json))
    }

    @Test
    fun messagesPreserveNanosecondTimestampsAndDatabaseIds() {
        val message = ChatMessage(
            sender = UserId("alice"),
            content = "你好，\"世界\"\n第二行",
            time = Instant.parse("2026-10-09T08:00:00.123456789Z"),
            roomId = RoomId("lobby"),
            id = 42,
        )
        assertEquals(message, Json.decodeFromString<ChatMessage>(Json.encodeToString(message)))
    }
}
