package preferences.backend

import org.kotlincrypto.macs.hmac.sha2.HmacSHA256
import org.kotlincrypto.random.CryptoRand
import preferences.modeling.PasswordHasher
import preferences.modeling.types.Password
import kotlin.io.encoding.Base64

/**
 * PBKDF2-HMAC-SHA256 in common Kotlin, so the JVM and Kotlin/Native servers hash the same way.
 * A hash is `pbkdf2-sha256$iterations$salt$key`; [verify] uses the stored iterations, so raising [iterations]
 * affects only new hashes.
 *
 * @param iterations the work factor; the default follows OWASP's recommendation for PBKDF2-HMAC-SHA256.
 */
class Pbkdf2PasswordHasher(private val iterations: Int = 600_000) : PasswordHasher {
    override fun hash(password: Password): String {
        val salt = ByteArray(16).also { CryptoRand.Default.nextBytes(it) }
        val key = derive(password, salt, iterations)
        return listOf(SCHEME, iterations, Base64.encode(salt), Base64.encode(key)).joinToString("$")
    }

    override fun verify(password: Password, hash: String): Boolean {
        val parts = hash.split("$")
        require(parts.size == 4 && parts[0] == SCHEME) { "Unsupported password hash" }
        val expected = Base64.decode(parts[3])
        return constantTimeEquals(derive(password, Base64.decode(parts[2]), parts[1].toInt()), expected)
    }

    /** One 32-byte block of PBKDF2 (RFC 8018), which is the whole output for HMAC-SHA256. */
    private fun derive(password: Password, salt: ByteArray, iterations: Int): ByteArray {
        val mac = HmacSHA256(password.value.encodeToByteArray())
        var block = mac.doFinal(salt + byteArrayOf(0, 0, 0, 1))
        val key = block.copyOf()
        repeat(iterations - 1) {
            block = mac.doFinal(block)
            for (i in key.indices) key[i] = (key[i].toInt() xor block[i].toInt()).toByte()
        }
        return key
    }

    private companion object {
        const val SCHEME = "pbkdf2-sha256"
    }
}

/** Compares in time independent of where the arrays differ, so timing does not reveal a matching prefix. */
internal fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var difference = 0
    for (i in a.indices) difference = difference or (a[i].toInt() xor b[i].toInt())
    return difference == 0
}
