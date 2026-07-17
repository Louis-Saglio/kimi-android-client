package fr.louissaglio.kimi.android.data.settings

data class ServerSettings(
    val serverUrl: String = DEFAULT_SERVER_URL,
    val username: String = "",
    val password: String = "",
) {
    val hasCredentials: Boolean get() = username.isNotBlank() || password.isNotBlank()

    companion object {
        const val DEFAULT_SERVER_URL = "http://100.64.0.1"
    }
}
