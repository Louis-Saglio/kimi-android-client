package fr.louissaglio.kimi.android.ui.common

import android.text.format.DateUtils
import java.time.Duration
import java.time.Instant

/** Platform relative timestamp (ticket 113): the framework owns the strings
 * ("5 minutes ago", "Yesterday", absolute date past a week) and their
 * localization — no app-side age branching. The no-Context overload uses system
 * resources, so this stays callable from any composable. Shared by the chat
 * bubble captions and the session list rows. */
internal fun formatTimestamp(instant: Instant): String =
    DateUtils.getRelativeTimeSpanString(
        instant.toEpochMilli(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
    ).toString()

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

/** Elapsed duration (ticket 092's jobs sheet): "mm:ss", switching to "h:mm:ss" past
 * an hour — the web client's task-panel convention. Negative clamps to zero. */
internal fun formatElapsed(duration: Duration): String {
    val totalSeconds = duration.seconds.coerceAtLeast(0)
    val hours = totalSeconds / 3_600
    val minutes = totalSeconds % 3_600 / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
