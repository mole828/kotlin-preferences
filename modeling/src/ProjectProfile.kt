package preferences.modeling

data class ProjectProfile(
    val name: String,
    val web: String,
    val backend: String,
    val modeling: String,
)

val projectProfile = ProjectProfile(
    name = "kotlin-preferences",
    web = "Kotlin/JS + kotlin-react",
    backend = "Ktor + Kotlin Multiplatform (JVM + Linux/macOS Native)",
    modeling = "Kotlin Multiplatform (JVM + JS + Linux/macOS Native)",
)
