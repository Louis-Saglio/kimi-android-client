package fr.louissaglio.kimi.android.data

/**
 * Error boundary type for the data layer: repositories map platform exceptions
 * (IOException, SerializationException) and non-zero envelope codes to this type
 * so raw library exceptions never reach ViewModels.
 */
sealed class DataError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    data object InvalidUrl : DataError("Invalid server URL")
    data object Auth : DataError("Authentication failed")
    class Network(cause: Throwable) : DataError("Network error", cause)
    class Http(val code: Int) : DataError("HTTP $code")
    class Api(val code: Int, val serverMessage: String) : DataError("Server error $code: $serverMessage")
    class MalformedResponse(cause: Throwable) : DataError("Malformed server response", cause)
}
