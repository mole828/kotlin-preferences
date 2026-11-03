package preferences.modeling.types

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class UserId(val value: String) {
    init {
        require(value.isNotBlank() && value.length <= 80) { "用户标识须为 1–80 字符" }
    }
}
