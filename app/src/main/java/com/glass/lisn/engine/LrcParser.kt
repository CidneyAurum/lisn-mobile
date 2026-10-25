package com.glass.lisn.engine

data class LrcLine(val timeMs: Long, val text: String)

/** Shared by the lyric quality gate, player UI and desktop lyric overlay. */
object LrcParser {
    private val lineRegex = Regex("""\[(\d{1,3})(?::(\d{1,2}))?(?:[.:](\d{1,3}))?]""")

    private fun fracMs(raw: String): Long = when (raw.length) {
        0 -> 0L
        1 -> raw.toLong() * 100
        2 -> raw.toLong() * 10
        else -> raw.take(3).toLong()
    }

    /** Accept standard [mm:ss.xx] and Kuwo [ss.xx] timestamps, including minute zero. */
    fun parse(lrc: String): List<LrcLine> {
        val out = mutableListOf<LrcLine>()
        for (raw in lrc.lineSequence()) {
            val stamps = lineRegex.findAll(raw).toList()
            if (stamps.isEmpty()) continue
            val text = raw.substring(stamps.last().range.last + 1).trim()
            for (m in stamps) {
                val (first, seconds, fraction) = m.destructured
                val timeMs = if (seconds.isEmpty()) first.toLong() * 1000 + fracMs(fraction)
                else first.toLong() * 60000 + seconds.toLong() * 1000 + fracMs(fraction)
                out += LrcLine(timeMs, text)
            }
        }
        return out.sortedBy { it.timeMs }
    }

    /** Require two timed lines to reject metadata-only and placeholder responses. */
    fun hasTimeline(raw: String): Boolean =
        raw.lineSequence().filter { lineRegex.containsMatchIn(it) }.take(2).count() == 2
}
