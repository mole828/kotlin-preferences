package preferences.modeling.types

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class RoomId(val value: String)
