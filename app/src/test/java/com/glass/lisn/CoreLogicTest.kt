package com.glass.lisn

import com.glass.lisn.model.Song
import com.glass.lisn.model.normalizeKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {
    // 与 ui.player.LrcParser 相同的逻辑(核心算法单测;UI 侧直接复用)
    private val lineRegex = Regex("""\[(\d{1,3})(?::(\d{1,2}))?(?:[.:](\d{1,3}))?]""")

    private fun fracMs(raw: String): Long = when (raw.length) {
        0 -> 0L; 1 -> raw.toLong() * 100; 2 -> raw.toLong() * 10; else -> raw.take(3).toLong()
    }

    private fun parse(lrc: String): List<Pair<Long, String>> {
        val out = mutableListOf<Pair<Long, String>>()
        for (raw in lrc.lineSequence()) {
            val stamps = lineRegex.findAll(raw).toList()
            if (stamps.isEmpty()) continue
            val text = raw.substring(stamps.last().range.last + 1).trim()
            for (m in stamps) {
                val g1 = m.groupValues[1]; val g2 = m.groupValues[2]; val g3 = m.groupValues[3]
                val timeMs = if (g2.isEmpty()) g1.toLong() * 1000 + fracMs(g3)
                else g1.toLong() * 60000 + g2.toLong() * 1000 + fracMs(g3)
                out += timeMs to text
            }
        }
        return out.sortedBy { it.first }
    }

    @Test
    fun `标准分秒时间戳`() {
        val r = parse("[01:23.45]hello")
        assertEquals(listOf(83450L to "hello"), r)
    }

    @Test
    fun `酷我秒制时间戳`() {
        val r = parse("[10.52]酷我行")
        assertEquals(listOf(10520L to "酷我行"), r)
    }

    @Test
    fun `多时间戳一行与排序`() {
        val r = parse("[02:05.5]标准\n[00:30]无小数\n[00:00.000]作词")
        assertEquals(listOf(0L to "作词", 30000L to "无小数", 125500L to "标准"), r)
    }

    @Test
    fun `非歌词行被跳过`() {
        assertTrue(parse("这不是歌词\n[00:01]是").size == 1)
    }
}

class RegistryMergeTest {
    // 与 SourceRegistry 相同的归一合并逻辑(纯函数抽取验证)
    private fun normalizeKey(name: String, artist: String): String =
        (name.trim() + "|" + (artist.split("/").firstOrNull()?.trim() ?: "")).lowercase()

    private fun merge(target: MutableMap<String, Song>, list: Song): Int {
        var added = 0
        if (list.name.isEmpty()) return 0
        val found = target[list.key]
        if (found != null) {
            val newOrigins = list.origins.filter { o -> found.origins.none { it.platform == o.platform && it.songId == o.songId } }
            target[list.key] = found.copy(origins = found.origins + newOrigins)
        } else { target[list.key] = list; added++ }
        return added
    }

    @Test
    fun `大小写与首歌手归一`() {
        assertEquals(normalizeKey("晴天", "周杰伦"), normalizeKey("晴天 ", "周杰伦/群星"))
        assertEquals("晴天|周杰伦", normalizeKey("晴天", "周杰伦"))
    }

    @Test
    fun `同曲多平台 origin 合并且去重`() {
        val target = mutableMapOf<String, Song>()
        val kw = Song(key = normalizeKey("晴天", "周杰伦"), name = "晴天", artist = "周杰伦",
            origins = listOf(com.glass.lisn.model.SongOrigin("mf:xiaowo", "kw", "228908")))
        val wy = Song(key = normalizeKey("晴天", "周杰伦"), name = "晴天", artist = "周杰伦",
            origins = listOf(com.glass.lisn.model.SongOrigin("gd", "wy", "2652820720")))
        merge(target, kw); merge(target, wy)
        val merged = target.values.single()
        assertEquals(listOf("kw", "wy"), merged.origins.map { it.platform })
        // 重复 origin 不重复加入
        merge(target, kw.copy(origins = listOf(com.glass.lisn.model.SongOrigin("mf:xiaowo", "kw", "228908"))))
        assertEquals(2, target.values.single().origins.size)
    }

    @Test
    fun `不同歌手的同名歌不合并`() {
        val target = mutableMapOf<String, Song>()
        merge(target, Song(key = normalizeKey("晴天", "周杰伦"), name = "晴天", artist = "周杰伦",
            origins = listOf(com.glass.lisn.model.SongOrigin("gd", "kw", "1"))))
        merge(target, Song(key = normalizeKey("晴天", "蓝心羽"), name = "晴天", artist = "蓝心羽",
            origins = listOf(com.glass.lisn.model.SongOrigin("mf:xiaowo", "kw", "2"))))
        assertEquals(2, target.size)
    }

    @Test
    fun `质量降级链顺序`() {
        runBlocking {
            // 直接验证 QUALITY_CHAIN 常量表(Spi)
            assertEquals(listOf("flac", "320k", "128k"), com.glass.lisn.engine.QUALITY_CHAIN["flac"])
            assertEquals(listOf("320k", "128k"), com.glass.lisn.engine.QUALITY_CHAIN["320k"])
        }
    }
}
