package preferences.modeling.types

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/** A plain-text password on its way to be hashed or checked. [toString] hides it, so it never reaches a log. */
@Serializable
@JvmInline
value class Password(val value: String) {
    init {
        require(value.length in 8..128) { "密码须为 8–128 字符" }
    }

    override fun toString(): String = "Password(***)"
}
