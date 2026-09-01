package fr.louissaglio.kimi.android.data.sessions

/**
 * Disambiguated workspace labels for the session list (ticket 114): each cwd is
 * labeled by its last path segment; while another cwd in the set produces the same
 * label, that cwd's next parent segment is prepended until the labels differ
 * (`/home/a/app` vs `/home/b/app` → `a/app` / `b/app`). A label depends on the whole
 * set, so it cannot live on [Session]. Null and segmentless cwds (`/`) get no label
 * and are absent from the result; identical cwds share one label. The grouped view
 * (ticket 115) reuses this for its group headers.
 */
fun workspaceLabelsFor(cwds: Collection<String?>): Map<String, String> {
    // Leaf-first path segments per distinct cwd; trailing slashes and empty segments
    // go away here, so "/x/app/" and "/x/app" label identically.
    val tails = cwds.filterNotNull().distinct().mapNotNull { cwd ->
        val segments = cwd.trimEnd('/').split('/').filter(String::isNotEmpty)
        if (segments.isEmpty()) null else cwd to segments.asReversed()
    }.toMap()
    // How many trailing segments each label currently shows.
    val depths = tails.mapValuesTo(HashMap()) { 1 }
    while (true) {
        val colliding = tails.keys
            .groupBy { cwd -> labelAt(tails.getValue(cwd), depths.getValue(cwd)) }
            .values.filter { it.size > 1 }
            .flatten()
        if (colliding.isEmpty()) break
        // Only colliding cwds extend, so non-colliding labels stay minimal. A cwd at
        // full depth cannot extend; when nothing in a collision can, the paths are
        // the same modulo leading slashes and the loop would never end.
        var extended = false
        for (cwd in colliding) {
            if (depths.getValue(cwd) < tails.getValue(cwd).size) {
                depths[cwd] = depths.getValue(cwd) + 1
                extended = true
            }
        }
        if (!extended) break
    }
    return tails.mapValues { (cwd, segments) -> labelAt(segments, depths.getValue(cwd)) }
}

private fun labelAt(leafFirstSegments: List<String>, depth: Int): String =
    leafFirstSegments.take(depth).asReversed().joinToString("/")
