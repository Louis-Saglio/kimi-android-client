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
