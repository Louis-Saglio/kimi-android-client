package fr.louissaglio.kimi.android.data.chat

// A task's output stream (docs/server-api.md, Sub-agents → Task frames and controls —
// server 2.5.0, ticket 180): `task.watch` streams `task.output` chunks positioned by
// their UTF-8 BYTE offset in the task's output — never by Kotlin's UTF-16 `length`.

/** One `task.output` frame: [text] starts at UTF-8 byte [offset] of [agentId]'s task
 * [taskId]'s output. The first chunk of a watch is the tail after `since_offset`
 * (possibly empty, e.g. `offset:0,text:""` for a silent command). */
data class TaskOutputChunk(
    val agentId: String,
    val taskId: String,
    val offset: Long,
    val text: String,
)

/**
 * The bounded output tail a watcher renders (ticket 180), assembled from
 * [TaskOutputChunk]s by byte offset. Immutable: [append] returns the new tail.
 *
 * - A chunk ending at or before [endOffset] is a duplicate (a re-watch overlapping
 *   what is already held) and is ignored; one straddling [endOffset] keeps only its
 *   new bytes.
 * - A chunk starting past [endOffset] opens a new [Segment]: the missing range stays
 *   visible in [render] as a marker, never silently concatenated.
 * - At most [maxBytes] UTF-8 bytes are retained: the oldest bytes are dropped (cut on
 *   a character boundary), so a long-running command cannot grow memory without
 *   limit. [startOffset] then reports where the retained tail begins.
 */
data class TaskOutputTail(
    val segments: List<Segment> = emptyList(),
    val maxBytes: Int = DEFAULT_MAX_BYTES,
) {
    /** A contiguous run of output: [text] spans bytes [offset] until [end]. */
    data class Segment(val offset: Long, val text: String, val byteCount: Int = text.utf8Size()) {
        val end: Long get() = offset + byteCount
    }

    /** True until the first chunk (even an empty one) arrived. */
    val isEmpty: Boolean get() = segments.isEmpty()

    /** The byte offset right after the newest byte held — the `since_offset` a re-watch
     * sends so nothing is lost or duplicated; null before the first chunk. */
    val endOffset: Long? get() = segments.lastOrNull()?.end

    /** The byte offset of the oldest byte held; null before the first chunk. */
    val startOffset: Long? get() = segments.firstOrNull()?.offset

    fun append(chunk: TaskOutputChunk): TaskOutputTail = append(chunk.offset, chunk.text)

    fun append(offset: Long, text: String): TaskOutputTail {
        val bytes = text.encodeToByteArray()
        val last = segments.lastOrNull() ?: return copy(segments = listOf(Segment(offset, text, bytes.size))).trimmed()
        val end = last.end
        if (offset + bytes.size <= end) return this
        val appended = when {
            offset > end -> segments + Segment(offset, text, bytes.size)
            else -> {
                // Contiguous (offset == end) or overlapping: only the bytes past end are new.
                val fresh = if (offset == end) text else bytes.copyOfRange((end - offset).toInt(), bytes.size).decodeToString()
                segments.dropLast(1) + last.copy(text = last.text + fresh, byteCount = last.byteCount + (offset + bytes.size - end).toInt())
            }
        }
        return copy(segments = appended).trimmed()
    }

    /** Drops the oldest bytes past [maxBytes], whole segments first, then a cut on a
     * UTF-8 character boundary inside the first one. */
    private fun trimmed(): TaskOutputTail {
        var excess = segments.sumOf { it.byteCount.toLong() } - maxBytes
        if (excess <= 0) return this
        val kept = ArrayDeque(segments)
        while (excess > 0 && kept.size > 1 && kept.first().byteCount <= excess) {
            excess -= kept.removeFirst().byteCount
        }
        if (excess > 0) {
            val first = kept.removeFirst()
            val bytes = first.text.encodeToByteArray()
            var cut = excess.toInt().coerceAtMost(bytes.size)
            // Never start inside a multi-byte character: skip its continuation bytes.
            while (cut < bytes.size && (bytes[cut].toInt() and 0xC0) == 0x80) cut++
            kept.addFirst(Segment(first.offset + cut, bytes.copyOfRange(cut, bytes.size).decodeToString(), bytes.size - cut))
        }
        return copy(segments = kept.toList())
    }

    /** The text to show: the segments in order, a marker line for each missing range,
     * and a leading marker when the tail does not start at the output's first byte. */
    fun render(): String = buildString {
        var previousEnd: Long? = null
        for (segment in segments) {
            val missing = segment.offset - (previousEnd ?: 0L)
            if (missing > 0) {
                if (isNotEmpty() && last() != '\n') append('\n')
                append(if (previousEnd == null) "[… $missing earlier bytes not shown …]" else "[… $missing bytes missing …]")
                append('\n')
            }
            append(segment.text)
            previousEnd = segment.end
        }
    }

    companion object {
        /** The retained tail: twice the 32 KiB a watch asks for. */
        const val DEFAULT_MAX_BYTES = 64 * 1024
    }
}

private fun String.utf8Size(): Int = encodeToByteArray().size
