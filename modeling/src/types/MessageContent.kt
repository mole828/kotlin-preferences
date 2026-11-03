package preferences.modeling.types

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class MessageContent(val value: String) {
    init {
        require(value.isNotBlank()) { "消息不能为空" }
        require(value.length <= 4000) { "消息最多 4000 字符" }
    }
}
