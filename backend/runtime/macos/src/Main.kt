package preferences.backend.macos

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv
import preferences.backend.runBackend

@OptIn(ExperimentalForeignApi::class)
fun main() = runBackend(jwtSecret = getenv("CHAT_JWT_SECRET")?.toKString())
