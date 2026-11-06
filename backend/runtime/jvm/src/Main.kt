package preferences.backend.jvm

import preferences.backend.runBackend

fun main() = runBackend(jwtSecret = System.getenv("CHAT_JWT_SECRET"))
