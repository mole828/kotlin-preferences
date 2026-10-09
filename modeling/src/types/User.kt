package preferences.modeling.types

import kotlinx.serialization.Serializable

@Serializable
data class User(
    val userId: UserId,
    val nick: String,
)
