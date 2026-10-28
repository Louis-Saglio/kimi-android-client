package fr.louissaglio.kimi.android.data

import kotlinx.serialization.json.JsonObject

/**
 * Error boundary type for the data layer: repositories map platform exceptions
 * (IOException, SerializationException) and non-zero envelope codes to this type
 * so raw library exceptions never reach ViewModels. Data classes so tests assert
 * equality instead of `is` + cast.
 */
sealed class DataError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    data object InvalidUrl : DataError("Invalid server URL")
    data object Auth : DataError("Authentication failed")
    data class Network(override val cause: Throwable) : DataError("Network error", cause)
    data class Http(val code: Int) : DataError("HTTP $code")
    /** [details] is the envelope's top-level error field (2.3.0); only the 40931
     * occupancy holder is read (ticket 169) — every other error leaves it null. */
    data class Api(val code: Int, val serverMessage: String, val details: JsonObject? = null) :
        DataError("Server error $code: $serverMessage")
    data class MalformedResponse(override val cause: Throwable) : DataError("Malformed server response", cause)
}

/** The one error → user-facing message mapping, shared by the ViewModels. */
internal fun Throwable.toUserMessage(): String = when (this) {
    is DataError.InvalidUrl -> "Invalid server URL — check Settings"
    is DataError.Auth -> "Authentication failed — check Settings"
    is DataError.Network -> "Server unreachable"
    is DataError.Http -> "Unexpected server response (HTTP $code)"
    is DataError.Api -> serverMessage.ifBlank { "Server error $code" }
    is DataError.MalformedResponse -> "Malformed server response"
    else -> message ?: "Unknown error"
}
