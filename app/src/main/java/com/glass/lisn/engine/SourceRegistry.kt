package com.glass.lisn.engine

import android.content.Context
import com.glass.lisn.model.HttpTemplateSnap
import com.glass.lisn.model.ProviderSnapshot
import com.glass.lisn.model.Song
import com.glass.lisn.model.SongOrigin
import com.glass.lisn.model.SourcesSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SourceRegistry(context: Context) {

    val gd = GdProvider()
    val lx = com.glass.lisn.engine.lx.LxSourceManager(context)
    val mf = MusicFreeManager(context)
    var providers: List<SourceProvider> = emptyList()
        private set
    var mode: String = "auto"
    private var httpTemplates: List<HttpSourceConfig> = emptyList()
    private val picCache = mutableMapOf<String, String>()

    // 解析失败黑名单:provider|platform|quality -> 失败时刻。TTL 内跨歌曲直接跳过,
    // 避免同一后端故障时对重复条目发起连环注定失败的请求(免费源 503 场景实测 12s -> <1s)
    private val failCache = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val failTtlMs = 60_000L
    private val mergedSongs = linkedMapOf<String, Song>()
    private val mergeMutex = Mutex()
    private var lastKeyword = ""
    private var lastPage = 0

    fun rebuild(templates: List<HttpSourceConfig>) {
        httpTemplates = templates
        val list = mutableListOf<SourceProvider>()
        for (entry in lx.entries) {
            if (!entry.enabled) continue
            lx.providerFor(entry)?.let { list += it }
        }
        for (entry in mf.entries) {
            if (!entry.enabled) continue
            mf.providerFor(entry)?.let { list += it }
        }
        for (t in templates) if (t.enabled != false) list += HttpGenericProvider(t)
        list += gd
        providers = list
    }

    fun blacklistCount(): Int = failCache.count { System.currentTimeMillis() < it.value }

    fun snapshot(): SourcesSnapshot = SourcesSnapshot(
        providers = providers.map { ProviderSnapshot(it.id, it.name, it.kind, it.caps, it.health) },
        mode = mode,
        blacklistedCount = blacklistCount(),
        lxEntries = lx.entries.map { e ->
            val host = lx.hostFor(e.id)
            com.glass.lisn.model.LxEntrySnap(
                id = e.id, name = e.name, enabled = e.enabled,
                platforms = host?.sources?.keys?.toList() ?: emptyList(),
                qualities = host?.sources?.values
                    ?.flatMap { it["qualitys"] as? List<*> ?: emptyList() }
                    ?.mapNotNull { com.glass.lisn.engine.str(it) }?.distinct() ?: emptyList(),
                remoteDate = e.remoteDate
            )
        },
        mfEntries = mf.snapEntries(),
        templates = httpTemplates.map { HttpTemplateSnap(it.id, it.name, it.urlTemplate, it.enabled) }
    )

    /** 聚合分页搜索:多源并发 + name|artist 归一合并,关键词翻页增量累计 */
    suspend fun search(keyword: String, page: Int): com.glass.lisn.model.SearchPage = coroutineScope {
        val searchers = providers.filter { it.caps.supportsSearch && it.health.status != "disabled" }
        val pages = searchers.map { p ->
            async(Dispatchers.IO) {
                runCatching { p.search(keyword, page) }.getOrElse { e ->
                    android.util.Log.w("LisnResolve", "FAIL ${p.id} search '$keyword' p=$page: ${e.message}")
                    emptyList()
                }
            }
        }.awaitAll()
        val lists = pages.flatten()
        val totalThisPage = pages.sumOf { it.size }

        mergeMutex.withLock {
            if (lastKeyword != keyword || page <= lastPage) {
                mergedSongs.clear(); lastKeyword = keyword; lastPage = 0
            }
            for (s in lists) {
                if (s.name.isEmpty()) continue
                val found = mergedSongs[s.key]
                if (found != null) {
                    val newOrigins = s.origins.filter { o ->
                        found.origins.none { it.platform == o.platform && it.songId == o.songId }
                    }
                    mergedSongs[s.key] = found.copy(
                        origins = found.origins + newOrigins,
                        album = found.album ?: s.album,
                        picUrl = found.picUrl ?: s.picUrl
                    )
                } else {
                    mergedSongs[s.key] = s
                }
            }
            lastPage = maxOf(lastPage, page)
            val hasMore = totalThisPage >= 20
            mergedSongs.values.firstOrNull()?.let { first ->
                android.util.Log.i("LisnResolve", "MERGE 首曲 key=${first.key} origins=${first.origins.joinToString(",") { it.platform + ":" + it.songId.take(10) }}")
            }
            com.glass.lisn.model.SearchPage(
                songs = mergedSongs.values.toList(),
                hasMore = hasMore,
                page = page
            )
        }
    }

    private suspend fun tryProvider(
        p: SourceProvider, song: Song, chain: List<String>
    ): ResolveResult? {
        // 插件类源(需要自家 musicItem 数据)只解析自有 origin;
        // GD/HTTP 模板类按 platform+songId 通用解析,可跨 provider 复用 origin
        val isPlugin = p.kind == "musicfree-plugin"
        val origins = song.origins.filter { o ->
            p.caps.platforms.contains(o.platform) && (!isPlugin || o.providerId == p.id)
        }
        if (origins.isEmpty()) return null
        for (q in chain) {
            if (!p.caps.qualities.contains(q)) continue
            for (o in origins) {
                val key = "${p.id}|${o.platform}|$q"
                val until = failCache[key]
                if (until != null && System.currentTimeMillis() < until) {
                    android.util.Log.d("LisnResolve", "SKIP ${p.id} $q ${o.platform}:${o.songId.take(16)} (TTL 黑名单)")
                    continue
                }
                try {
                    val url = withTimeoutMs(15000) { p.resolveUrl(o, q) }
                    failCache.remove(key)
                    android.util.Log.i("LisnResolve", "OK ${p.id} $q ${o.platform}:${o.songId.take(24)}")
                    return ResolveResult(url, p.id, o.platform, q)
                } catch (e: Throwable) {
                    failCache[key] = System.currentTimeMillis() + failTtlMs
                    android.util.Log.w("LisnResolve", "FAIL ${p.id} $q ${o.platform}:${o.songId.take(24)}: ${e.message}")
                }
            }
        }
        return null
    }

    /** 质量降级 + 多源按序竞速解析 */
    suspend fun resolveUrl(song: Song, quality: String, pinnedProviderId: String? = null): ResolveResult {
        val chain = QUALITY_CHAIN[quality] ?: listOf("320k", "128k")
        if (mode == "manual" && pinnedProviderId != null) {
            val p = providers.find { it.id == pinnedProviderId }
                ?: throw ResolveError("指定音源不存在或未加载", "manual-blocked")
            tryProvider(p, song, chain)?.let { return it }
            throw ResolveError("锁定音源解析失败:${p.health.lastError ?: ""}", "manual-blocked")
        }
        for (p in providers) {
            if (p.health.status == "disabled") continue
            tryProvider(p, song, chain)?.let { return it }
        }
        val lastErr = providers.mapNotNull { it.health.lastError }.firstOrNull() ?: ""
        throw ResolveError("所有音源均无法解析" + (if (lastErr.isNotEmpty()) ":$lastErr" else ""))
    }

    suspend fun getPic(song: Song): String? {
        picCache[song.key]?.let { return it }
        for (o in song.origins) {
            val p = providers.find {
                it.health.status != "disabled" && it.caps.platforms.contains(o.platform)
            } ?: continue
            try {
                val url = withTimeoutMs(8000) { p.getPic(o) }
                if (!url.isNullOrEmpty()) { picCache[song.key] = url; return url }
            } catch (_: Throwable) { /* next */ }
        }
        return null
    }

    /** 至少两条带时间戳的行,否则视为占位/空歌词 */
    private fun lyricHasTimeline(raw: String): Boolean {
        val ts = Regex("' + bs + bs + '[1-9][0-9]{1,2}:[0-9]{1,2}")
        var n = 0
        for (line in raw.lineSequence()) {
            if (ts.containsMatchIn(line)) { n++; if (n >= 2) return true }
        }
        return false
    }

    /** 歌词质量门:歌名非现场版但歌词首行标注 现场/Live → 视为错版,换源 */
    private fun lyricMatchesSong(raw: String, song: Song): Boolean {
        val n = song.name
        if (n.contains("现场") || n.contains("Live", true) || n.contains("演唱会")) return true
        val head = raw.lineSequence().take(6).joinToString("\n")
        return !(head.contains("现场") || head.contains("演唱会") ||
            Regex("\\bLive\\b", RegexOption.IGNORE_CASE).containsMatchIn(head))
    }

    suspend fun getLyric(song: Song): String? {
        // 同一平台的所有 provider 依次尝试(lx 源多无歌词能力,须轮到 GD/HTTP 等)
        for (o in song.origins) {
            for (p in providers) {
                if (p.health.status == "disabled") continue
                if (!p.caps.platforms.contains(o.platform)) continue
                try {
                    val lrc = withTimeoutMs(8000) { p.getLyric(o) }
                    android.util.Log.i("LisnLyric", "provider=${p.id} platform=${o.platform} len=${lrc?.length ?: -1}")
                    if (!lrc.isNullOrEmpty() && lyricMatchesSong(lrc, song) && lyricHasTimeline(lrc)) return lrc
                } catch (e: Throwable) {
                    android.util.Log.w("LisnLyric", "provider=${p.id} platform=${o.platform} fail: ${e.message}")
                }
            }
        }
        // 跨平台兜底:播放平台无歌词能力(tx 等)时,按「歌名+歌手」在 GD 搜同名曲取词
        try {
            val lrc = withTimeoutMs(12000) { gdLyricFallback(song) }
            android.util.Log.i("LisnLyric", "gdFallback len=" + (lrc?.length ?: -1))
            if (!lrc.isNullOrEmpty()) return lrc
        } catch (e: Throwable) {
            android.util.Log.w("LisnLyric", "gdFallback fail: " + e.message)
        }
        return null
    }

    private suspend fun gdLyricFallback(song: Song): String? {
        val gd = providers.firstOrNull { it.id == "gd" } ?: return null
        val hits = gd.search(song.name.trim() + " " + song.artist.trim(), 1)
        val hit = hits.firstOrNull { h -> h.name.contains(song.name.trim()) } ?: return null
        val o = hit.origins.firstOrNull() ?: return null
        val lrc = gd.getLyric(o)
        if (lrc.isNullOrEmpty() || !lyricMatchesSong(lrc, song) || !lyricHasTimeline(lrc)) return null
        return lrc
    }
}
