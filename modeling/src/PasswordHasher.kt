package preferences.modeling

import preferences.modeling.types.Password

/** One-way password hashing. A hash records its own parameters, so hashes made with older settings still verify. */
interface PasswordHasher {
    fun hash(password: Password): String
    fun verify(password: Password, hash: String): Boolean
}
