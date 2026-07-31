package fr.louissaglio.kimi.android.ui.common

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Web-style timestamp (ticket 039): locale short date + time, device zone. Shared by
 * the chat bubble captions and the session list rows. */
private val TimestampFormat: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT, FormatStyle.SHORT)

internal fun formatTimestamp(instant: Instant): String =
    TimestampFormat.withZone(ZoneId.systemDefault()).format(instant)

/** The one k/M scaler (ticket 071): one TRUNCATED decimal, dropped when zero —
 * "25.5k", "1M". Token counts and byte sizes both go through it, so the two cannot
 * drift back into different rounding policies. */
private fun scaled(count: Long, divisor: Long, suffix: String): String {
    val whole = count / divisor
    val tenth = count % divisor * 10 / divisor
    return if (tenth == 0L) "$whole$suffix" else "$whole.$tenth$suffix"
}

/** Compact token count: "999", "25.5k", "1M". Shared by the context ring, the
 * compaction divider and the model picker's context-size labels. */
internal fun formatTokenCount(count: Long): String = when {
    count >= 1_000_000 -> scaled(count, 1_000_000, "M")
    count >= 1_000 -> scaled(count, 1_000, "k")
    else -> "$count"
}

/** Byte size: "512 B", "340.5 kB", "1.2 MB" — decimal units, like the web client's
 * chip labels. */
internal fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> scaled(bytes, 1_000_000, " MB")
    bytes >= 1_000 -> scaled(bytes, 1_000, " kB")
    else -> "$bytes B"
}
