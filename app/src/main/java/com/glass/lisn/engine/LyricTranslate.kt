package com.glass.lisn.engine

/** 外语歌词识别 + 中文翻译合并(GD tlyric) */
object LyricTranslate {

    private val stamp = Regex("^[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val stampAny = Regex("^\\[[^\\]]*\\]")

    private fun parseMs(m: MatchResult): Long {
        val g = m.groupValues
        val frac = if (g[3].isEmpty()) 0L else g[3].padEnd(3, '0').take(3).toLong()
        return g[1].toLong() * 60000 + g[2].toLong() * 1000 + frac
    }

    /** 外语判定:含日语假名/韩语谚文即外语;拉丁字母占字母总数 > 0.7 也视为外语 */
    fun isForeign(lrc: String): Boolean {
        val sample = lrc.lineSequence()
            .map { stampAny.replace(it, "").trim() }
            .filter { it.isNotEmpty() && !Regex("^[^\\s:：]{1,6}[：:]").containsMatchIn(it) && !it.contains("翻译") }
            .take(10)
            .joinToString(" ")
        if (sample.isEmpty()) return false
        if (Regex("[ぁ-んァ-ヶ]").containsMatchIn(sample)) return true
        if (Regex("[가-힣]").containsMatchIn(sample)) return true
        var latin = 0
        var han = 0
        for (ch in sample) {
            if (ch in 'a'..'z' || ch in 'A'..'Z') latin++
            if (ch.code in 0x4e00..0x9fff) han++
        }
        return (latin + han) > 0 && latin.toDouble() / (latin + han) > 0.7
    }

    /** 按译文时间戳(±200ms 就近)替换原文行文本;时间轴保持原文 */
    fun merge(origLrc: String, tlyric: String): String {
        data class T(val ms: Long, val text: String)
        val tmap = mutableListOf<T>()
        for (line in tlyric.lineSequence()) {
            val m = stamp.find(line) ?: continue
            val text = stampAny.replace(line, "").trim()
            if (text.isNotEmpty()) tmap.add(T(parseMs(m), text))
        }
        if (tmap.isEmpty()) return origLrc
        return origLrc.lineSequence().joinToString("\n") { line ->
            val stampM = stamp.find(line)
            val head = stampAny.find(line)?.value
            if (stampM == null || head == null) line
            else {
                val ms = parseMs(stampM)
                var best: T? = null
                for (t in tmap) {
                    if (Math.abs(t.ms - ms) <= 200 && (best == null || Math.abs(t.ms - ms) < Math.abs(best.ms - ms))) best = t
                }
                if (best != null) head + best.text else line
            }
        }
    }
}
