package com.glass.lisn

import com.glass.lisn.engine.LrcLine
import com.glass.lisn.engine.LrcParser
import com.glass.lisn.engine.QUALITY_CHAIN
import com.glass.lisn.engine.SearchResults
import com.glass.lisn.model.Song
import com.glass.lisn.model.SongOrigin
import org.junit.Assert.*
import org.junit.Test

class LrcParserTest {
    @Test fun `标准分秒时间戳`() {
        assertEquals(listOf(LrcLine(83450, "hello")), LrcParser.parse("[01:23.45]hello"))
    }

    @Test fun `酷我秒制时间戳`() {
        assertEquals(listOf(LrcLine(10520, "酷我行")), LrcParser.parse("[10.52]酷我行"))
    }

    @Test fun `多时间戳一行与排序`() {
        assertEquals(
            listOf(LrcLine(0, "作词"), LrcLine(2050, "重复行"), LrcLine(4005, "重复行")),
            LrcParser.parse("[00:02.05][00:04.005]重复行\n[00:00.000]作词")
        )
    }

    @Test fun `非歌词行被跳过`() {
        assertEquals(listOf(LrcLine(1000, "是")), LrcParser.parse("这不是歌词\n[00:01]是"))
    }

    @Test fun `歌词质量门接受零分钟和秒制时间戳`() {
        assertTrue(LrcParser.hasTimeline("[00:01.20]第一行\n[00:05.30]第二行"))
        assertTrue(LrcParser.hasTimeline("[1.20]第一行\n[5.30]第二行"))
    }

    @Test fun `歌词质量门拒绝元数据和单行占位符`() {
        assertFalse(LrcParser.hasTimeline("[ti:歌名]\n[ar:歌手]"))
        assertFalse(LrcParser.hasTimeline("[00:00.00]暂无歌词"))
        assertFalse(LrcParser.hasTimeline("[00:01][00:02]只有一行"))
    }
}

class RegistryMergeTest {
    private fun song(name: String, artist: String, provider: String, id: String, pic: String? = null) =
        Song("old-key", name, artist, origins = listOf(SongOrigin(provider, "kw", id)), picUrl = pic)

    @Test fun `实际强归一化合并空白标点全角和合作歌手`() {
        val first = song("Ｈｅｌｌｏ－世界", "Artist feat. Guest", "gd", "1")
        val second = song(" hello 世界 ", "artist/Guest", "mf:kw", "2")
        val merged = SearchResults.merge(listOf(first), listOf(second)).single()
        assertEquals("hello世界|artist", merged.key)
        assertEquals(2, merged.origins.size)
    }

    @Test fun `同平台不同插件的数据都保留而重复origin去重`() {
        val first = song("晴天", "周杰伦", "mf:first", "1")
        val second = song("晴天", "周杰伦", "mf:second", "1")
        val merged = SearchResults.merge(listOf(first), listOf(first, second)).single()
        assertEquals(listOf("mf:first", "mf:second"), merged.origins.map { it.providerId })
    }

    @Test fun `不同歌手的同名歌不合并`() {
        assertEquals(2, SearchResults.merge(emptyList(), listOf(
            song("晴天", "周杰伦", "gd", "1"), song("晴天", "蓝心羽", "gd", "2")
        )).size)
    }

    @Test fun `翻页保留已补封面且补齐专辑`() {
        val first = song("晴天", "周杰伦", "gd", "1", "https://cover")
        val second = song("晴天", "周杰伦", "mf:kw", "2").copy(album = "叶惠美")
        val merged = SearchResults.merge(listOf(first), listOf(second)).single()
        assertEquals("https://cover", merged.picUrl)
        assertEquals("叶惠美", merged.album)
    }

    @Test fun `封面批量更新且无变化时不分配新列表`() {
        val songs = SearchResults.merge(emptyList(), listOf(
            song("晴天", "周杰伦", "gd", "1"), song("七里香", "周杰伦", "gd", "2")
        ))
        val updated = SearchResults.withCovers(songs, songs.associate { it.key to "https://${it.key}" })
        assertTrue(updated.all { it.picUrl != null })
        assertSame(updated, SearchResults.withCovers(updated, mapOf("absent" to "https://cover")))
        assertSame(updated, SearchResults.withCovers(updated, updated.associate { it.key to it.picUrl!! }))
    }

    @Test fun `质量降级链顺序`() {
        assertEquals(listOf("flac", "320k", "128k"), QUALITY_CHAIN["flac"])
        assertEquals(listOf("320k", "128k"), QUALITY_CHAIN["320k"])
    }
}
