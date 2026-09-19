package com.glass.lisn.engine

import com.glass.lisn.model.Song
import com.glass.lisn.model.SongOrigin
import com.glass.lisn.model.SourceCaps
import com.glass.lisn.model.SourceHealth
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * GD音乐台(music-api.gdstudio.xyz)
 * 实测支持的 source 值:netease / kuwo(kugou/qq/migu 返回 400)
 */
class GdProvider : SourceProvider {
    override val id = "gd"
    override val name = "GD音乐台"
    override val kind = "gd"
    override val caps = SourceCaps(platforms = listOf("wy", "kw"), qualities = listOf("320k", "128k", "flac"), supportsSearch = true)
    override val health = SourceHealth(status = "loading")

    private data class GdSource(val gd: String, val platform: String)

    private val gdSources = listOf(GdSource("netease", "wy"), GdSource("kuwo", "kw"))
    private val platformToGd = mapOf("wy" to "netease", "kw" to "kuwo")

    private data class GdSearchItem(
        val id: String? = null, val url_id: String? = null, val lyric_id: String? = null,
        val pic_id: String? = null, val name: String? = null, val artist: List<String>? = null,
        val album: String? = null
    )

    private fun gdFetch(params: Map<String, Any?>): Any? {
        val qs = params.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value.toString())}" }
        val text = httpGetText("$BASE?$qs", headers = mapOf("Referer" to "https://music.gdstudio.xyz/"), timeoutMs = 12000)
        return runCatching { jsonToJsonNative(json.parseToJsonElement(text)) }.getOrDefault(text)
    }

    private suspend fun gdFetchT(params: Map<String, Any?>): Any? =
        withTimeoutMs(15000, "GD 请求超时") { gdFetch(params) }

    override suspend fun search(keyword: String, page: Int): List<Song> = coroutineScope {
        val perSource = gdSources.map { src ->
            async {
                val out = mutableListOf<Song>()
                try {
                    val t0 = System.currentTimeMillis()
                    // 实测:count=每页数量(上限75),pages=页码(零重叠,真翻页)
                    val list = gdFetchT(mapOf(
                        "types" to "search", "source" to src.gd, "name" to keyword,
                        "count" to 75, "pages" to page
                    ))
                    markOk(System.currentTimeMillis() - t0)
                    (list as? List<*>)?.forEach { item ->
                        item as? Map<*, *> ?: return@forEach
                        val name = str(item["name"]) ?: return@forEach
                        val songId = str(item["url_id"]) ?: str(item["id"]) ?: return@forEach
                        val artist = (item["artist"] as? List<*>)?.mapNotNull { str(it) }?.joinToString("/")?.ifEmpty { null } ?: "未知歌手"
                        val extra = mutableMapOf<String, String>()
                        str(item["pic_id"])?.let { extra["picId"] = it }
                        str(item["lyric_id"])?.let { extra["lyricId"] = it }
                        str(item["name"])?.let { extra["gdName"] = it }
                        out += Song(
                            key = (name + "|" + artist).lowercase(),
                            name = name,
                            artist = artist,
                            album = str(item["album"]),
                            origins = listOf(SongOrigin(
                                providerId = id, platform = src.platform, songId = songId, extra = extra
                            ))
                        )
                    }
                } catch (e: Exception) {
                    markFail(e)
                    android.util.Log.w("LisnResolve", "FAIL gd-search ${src.gd} p=$page: ${e.message}")
                }
                android.util.Log.i("LisnResolve", "SEARCH gd ${src.gd} p=$page -> ${out.size} 条 firstKey=${out.firstOrNull()?.key} firstId=${out.firstOrNull()?.origins?.firstOrNull()?.songId}")
                out
            }
        }.awaitAll()
        perSource.flatten()
    }

    override suspend fun resolveUrl(origin: SongOrigin, quality: String): String {
        val gd = platformToGd[origin.platform] ?: throw RuntimeException("GD 不支持平台 ${origin.platform}")
        val br = when (quality) { "128k" -> 128; "flac" -> 999; else -> 320 }
        val j = withTimeoutMs(12000, "GD 取流超时") { gdFetch(mapOf("types" to "url", "source" to gd, "id" to origin.songId, "br" to br)) }
        val url = if (j is String) j else str((j as? Map<*, *>)?.get("url"))
        if (url.isNullOrEmpty() || !url.startsWith("http")) throw RuntimeException("GD 无可用 URL")
        return url
    }

    override suspend fun getPic(origin: SongOrigin): String? {
        val picId = origin.extra["picId"] ?: return null
        val gd = platformToGd[origin.platform] ?: return null
        return try {
            val j = gdFetch(mapOf("types" to "pic", "source" to gd, "id" to picId, "size" to 300))
            val url = if (j is String) j else str((j as? Map<*, *>)?.get("url"))
            url?.takeIf { it.startsWith("http") }
        } catch (_: Throwable) { null }
    }

    override suspend fun getLyric(origin: SongOrigin): String? {
        val lyricId = origin.extra["lyricId"] ?: return null
        val gd = platformToGd[origin.platform] ?: return null
        return try {
            val j = gdFetch(mapOf("types" to "lyric", "source" to gd, "id" to lyricId))
            if (j is String) (if (j.startsWith("http")) null else j)
            else str((j as? Map<*, *>)?.get("lyric"))
        } catch (_: Throwable) { null }
    }

    override suspend fun test(): Pair<Boolean, String> = try {
        val songs = search("晴天", 1)
        (songs.isNotEmpty()) to "搜索返回 ${songs.size} 条"
    } catch (e: Exception) {
        false to (e.message ?: e.toString())
    }

    companion object { private const val BASE = "https://music-api.gdstudio.xyz/api.php" }
}
