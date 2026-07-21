package fr.louissaglio.kimi.android.data.settings

data class ServerSettings(
    val serverUrl: String = DEFAULT_SERVER_URL,
    val token: String = "",
) {
    val hasCredentials: Boolean get() = token.isNotBlank()

    companion object {
        const val DEFAULT_SERVER_URL = "http://100.64.0.1"
    }
}
