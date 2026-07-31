package fr.louissaglio.kimi.android.data.network

import fr.louissaglio.kimi.android.data.DataError
import kotlinx.serialization.Serializable

/**
 * Generic REST envelope of the Kimi Code server. Note per docs/server-api.md:
 * errors come back as HTTP 200 with a non-zero [code], so [code] must be checked
 * in addition to the HTTP status.
 */
@Serializable
data class Envelope<T>(
    val code: Int = 0,
    val msg: String = "",
    val data: T? = null,
)

/**
 * Standard unwrap: a non-zero [Envelope.code] maps to [DataError.Api], and a
 * payload-free success (null [Envelope.data]) to a user-facing Api error — the
 * message reaches the snackbar verbatim via toUserMessage. Decode failures are
 * caught upstream by the ApiClient scaffold (MalformedResponse).
 */
fun <T> Envelope<T>.toResult(): Result<T> = when {
    code != 0 -> Result.failure(DataError.Api(code, msg))
    data == null -> Result.failure(DataError.Api(code, "Unexpected empty server response"))
    else -> Result.success(data)
}

/**
 * Nullable-payload unwrap for endpoints whose success may legitimately carry no
 * `data` (session status, server config): [Envelope.data] passes through as-is
 * and the caller decides what a null payload means.
 */
fun <T> Envelope<T>.toNullableResult(): Result<T?> = when {
    code != 0 -> Result.failure(DataError.Api(code, msg))
    else -> Result.success(data)
}

/** Code-only unwrap for acks whose `data` carries nothing the app consumes. */
fun Envelope<*>.toAckResult(): Result<Unit> = when {
    code != 0 -> Result.failure(DataError.Api(code, msg))
    else -> Result.success(Unit)
}
