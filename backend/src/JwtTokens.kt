package preferences.backend

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.kotlincrypto.macs.hmac.sha2.HmacSHA256
import preferences.modeling.types.UserId
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Issues and verifies HS256 JWTs whose subject is the signed-in user. Ktor's JWT plugin is JVM-only,
 * so this covers the one algorithm the server uses, in common Kotlin.
 *
 * A token cannot be revoked before it expires; [lifetime] bounds how long a leaked token stays usable.
 * Every server instance must use the same [secret], or tokens from one instance fail on another.
 */
class JwtTokens(private val secret: ByteArray, private val lifetime: Duration = 1.days) {
    init {
        require(secret.size >= 32) { "The JWT secret must be at least 32 bytes" }
    }

    fun issue(user: UserId, now: Instant = Clock.System.now()): String {
        val claims = Claims(sub = user.value, exp = (now + lifetime).epochSeconds)
        val unsigned = "$HEADER.${encode(json.encodeToString(claims).encodeToByteArray())}"
        return "$unsigned.${encode(sign(unsigned))}"
    }

    /** The token's user, or null when the token is malformed, signed with another key, or expired. */
    fun verify(token: String, now: Instant = Clock.System.now()): UserId? {
        val parts = token.split(".")
        // Only tokens with this exact header are accepted, which rules out "alg": "none" and other algorithms.
        if (parts.size != 3 || parts[0] != HEADER) return null
        val signature = runCatching { decode(parts[2]) }.getOrNull() ?: return null
        if (!constantTimeEquals(sign("${parts[0]}.${parts[1]}"), signature)) return null
        val claims = runCatching { json.decodeFromString<Claims>(decode(parts[1]).decodeToString()) }.getOrNull()
        if (claims == null || now.epochSeconds >= claims.exp) return null
        return runCatching { UserId(claims.sub) }.getOrNull()
    }

    private fun sign(unsigned: String): ByteArray = HmacSHA256(secret).doFinal(unsigned.encodeToByteArray())

    @Serializable
    private data class Claims(val sub: String, val exp: Long)

    private companion object {
        val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
        val json = Json { ignoreUnknownKeys = true }
        val HEADER = encode("""{"alg":"HS256","typ":"JWT"}""".encodeToByteArray())

        fun encode(bytes: ByteArray) = base64Url.encode(bytes)
        fun decode(text: String) = base64Url.decode(text)
    }
}
