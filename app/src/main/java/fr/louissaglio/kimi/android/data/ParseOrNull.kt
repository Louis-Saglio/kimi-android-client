package fr.louissaglio.kimi.android.data

import java.time.format.DateTimeParseException

/**
 * Runs a wire parse, returning null (after [onFailure]) when the input is malformed.
 * Only the parsers' own failures count: [IllegalArgumentException] covers
 * kotlinx.serialization (`SerializationException` and the `jsonObject`/`jsonPrimitive`
 * casts extend it) and [DateTimeParseException] covers `Instant.parse`. Anything else —
 * an `Error`, a mapper bug, a `CancellationException` — propagates, so a crash stays a
 * crash instead of becoming a silently dropped frame (`runCatching` would catch all of
 * them).
 */
internal inline fun <T> parseOrNull(onFailure: (RuntimeException) -> Unit = {}, block: () -> T): T? =
    try {
        block()
    } catch (e: IllegalArgumentException) {
        onFailure(e)
        null
    } catch (e: DateTimeParseException) {
        onFailure(e)
        null
    }
