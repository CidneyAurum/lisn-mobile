package com.glass.lisn.engine

import android.content.Context
import com.glass.lisn.model.MfEntrySnap
import com.glass.lisn.model.Song
import com.glass.lisn.model.SongOrigin
import com.glass.lisn.model.SourceCaps
import com.glass.lisn.model.SourceHealth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

@Serializable
data class MfSourceEntry(
    val id: String,
    val name: String,
    val platform: String,
    val enabled: Boolean,
    val file: String,
    val repo: String,
    val branch: String,
    val rawUrl: String,
    val jsdelivrUrl: String,
    val version: String? = null,
    val remoteDate: String? = null
)

/** keep-alive 的 MusicFree 插件源(搜索走各平台官方接口,取流走后端) */
val DEFAULT_MF_SOURCES = listOf(
    MfSourceEntry("xiaowo", "小蜗音乐(酷我)", "kw", true, "Music_Free/xiaowo.js", "Huibq/keep-alive", "master",
        "https://raw.githubusercontent.com/Huibq/keep-alive/master/Music_Free/xiaowo.js",
        "https://fastly.jsdelivr.net/gh/Huibq/keep-alive@master/Music_Free/xiaowo.js"),
    MfSourceEntry("xiaogou", "小蜗·狗(酷狗)", "kg", true, "Music_Free/xiaogou.js", "Huibq/keep-alive", "master",
        "https://raw.githubusercontent.com/Huibq/keep-alive/master/Music_Free/xiaogou.js",
        "https://fastly.jsdelivr.net/gh/Huibq/keep-alive@master/Music_Free/xiaogou.js"),
    MfSourceEntry("xiaoqiu", "小秋音乐(QQ音乐)", "tx", true, "Music_Free/xiaoqiu.js", "Huibq/keep-alive", "master",
        "https://raw.githubusercontent.com/Huibq/keep-alive/master/Music_Free/xiaoqiu.js",
        "https://fastly.jsdelivr.net/gh/Huibq/keep-alive@master/Music_Free/xiaoqiu.js"),
    MfSourceEntry("xiaoyun", "小芸音乐(网易云)", "wy", true, "Music_Free/xiaoyun.js", "Huibq/keep-alive", "master",
        "https://raw.githubusercontent.com/Huibq/keep-alive/master/Music_Free/xiaoyun.js",
        "https://fastly.jsdelivr.net/gh/Huibq/keep-alive@master/Music_Free/xiaoyun.js"),
    MfSourceEntry("xiaomi", "小蜜音乐(咪咕)", "mg", false, "Music_Free/xiaomi.js", "Huibq/keep-alive", "master",
        "https://raw.githubusercontent.com/Huibq/keep-alive/master/Music_Free/xiaomi.js",
        "https://fastly.jsdelivr.net/gh/Huibq/keep-alive@master/Music_Free/xiaomi.js")
)

class MusicFreeManager(context: Context) {

    private val dir = File(context.filesDir, "mf-sources").apply { mkdirs() }
    private val scriptsDir = File(dir, "scripts").apply { mkdirs() }
    private val configFile = File(dir, "mf-config.json")

    var entries: List<MfSourceEntry> = emptyList()
        private set
    private val hosts = mutableMapOf<String, MusicFreeHost>()

    @Synchronized
    fun init() {
        val loaded = runCatching {
            json.decodeFromString(MfConfig.serializer(), configFile.readText()).entries
        }.getOrNull()
        val list = (loaded?.takeIf { it.isNotEmpty() } ?: DEFAULT_MF_SOURCES).toMutableList()
        for (d in DEFAULT_MF_SOURCES) if (list.none { it.id == d.id }) list += d
        entries = list
        save()
    }

    @Serializable
    private data class MfConfig(val entries: List<MfSourceEntry>)

    private fun save() {
        runCatching { configFile.writeText(json.encodeToString(MfConfig.serializer(), MfConfig(entries))) }
    }

    private fun scriptFile(id: String) = File(scriptsDir, "mf-$id.js")

    private suspend fun fetchCode(entry: MfSourceEntry): String = withContext(Dispatchers.IO) {
        var lastErr: Throwable? = null
        for (u in listOf(entry.rawUrl, entry.jsdelivrUrl)) {
            try {
                val text = httpGetText(u, headers = mapOf("User-Agent" to "LISN-Android/0.1"), timeoutMs = 16000)
                if (text.length < 40) throw RuntimeException("内容无效")
                return@withContext text
            } catch (e: Throwable) { lastErr = e }
        }
        throw lastErr ?: RuntimeException("下载插件脚本失败")
    }

    suspend fun loadAll(): Pair<List<String>, List<Pair<String, String>>> {
        val ok = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()
        for (entry in entries) {
            if (!entry.enabled) continue
            try {
                var code = runCatching { scriptFile(entry.id).takeIf { it.length() > 40 }?.readText() }.getOrNull()
                if (code.isNullOrEmpty()) {
                    code = fetchCode(entry)
                    scriptFile(entry.id).writeText(code)
                }
                val host = MusicFreeHost(entry.id, entry.name)
                host.load(code)
                synchronized(hosts) { hosts[entry.id] = host }
                ok += entry.id
                android.util.Log.i("LisnMf", "插件加载成功: ${entry.id} v${host.version ?: "?"}")
            } catch (e: Throwable) {
                failed += entry.id to (e.message ?: e.toString())
                android.util.Log.e("LisnMf", "插件加载失败: ${entry.id}: ${e.message}")
            }
        }
        return ok to failed
    }

    fun setEnabled(id: String, enabled: Boolean) {
        entries = entries.map { if (it.id == id) it.copy(enabled = enabled) else it }
        if (!enabled) synchronized(hosts) { hosts.remove(id)?.close() }
        save()
    }

    suspend fun upgrade(id: String): Pair<Boolean, String> {
        val entry = entries.find { it.id == id } ?: return false to "未知音源 $id"
        return try {
            val code = fetchCode(entry)
            runCatching {
                val backup = File(dir, "backup").apply { mkdirs() }
                val old = scriptFile(id)
                if (old.exists()) File(backup, "mf-$id.${System.currentTimeMillis()}.js").writeText(old.readText())
            }
            scriptFile(id).writeText(code)
            val host = MusicFreeHost(id, entry.name)
            host.load(code)
            synchronized(hosts) { hosts[id]?.close(); hosts[id] = host }
            entries = entries.map { if (it.id == id) it.copy(remoteDate = java.time.Instant.now().toString()) else it }
            save()
            true to "已升级到最新"
        } catch (e: Throwable) {
            false to (e.message ?: e.toString())
        }
    }

    suspend fun checkUpdates() {
        for (entry in entries) {
            if (entry.repo.isEmpty()) continue
            try {
                val u = "https://api.github.com/repos/${entry.repo}/commits?path=" +
                    java.net.URLEncoder.encode(entry.file, "UTF-8") + "&per_page=1"
                val text = httpGetText(u, headers = mapOf("Accept" to "application/vnd.github+json"), timeoutMs = 10000)
                val arr = jsonToJsonNative(json.parseToJsonElement(text)) as? List<*> ?: continue
                val first = arr.firstOrNull() as? Map<*, *> ?: continue
                val commit = first["commit"] as? Map<*, *> ?: continue
                val date = str(commit["committer"]?.let { (it as? Map<*, *>)?.get("date") })
                    ?: str(commit["author"]?.let { (it as? Map<*, *>)?.get("date") }) ?: continue
                entries = entries.map { if (it.id == entry.id) it.copy(remoteDate = date) else it }
            } catch (_: Throwable) { /* 下次再查 */ }
        }
        save()
    }

    fun snapEntries(): List<MfEntrySnap> = entries.map {
        MfEntrySnap(
            id = it.id, name = it.name, platform = it.platform, enabled = it.enabled,
            repo = it.repo, version = synchronized(hosts) { hosts[it.id]?.version } ?: it.version,
            remoteDate = it.remoteDate
        )
    }

    /** 生成该插件的 Provider(未加载返回 null) */
    fun providerFor(entry: MfSourceEntry): SourceProvider? {
        val host = synchronized(hosts) { hosts[entry.id] } ?: return null
        return MfProvider(entry, host, this)
    }

    class MfProvider(
        private val entry: MfSourceEntry,
        private val host: MusicFreeHost,
        private val manager: MusicFreeManager
    ) : SourceProvider {
        override val id = "mf:${entry.id}"
        override val name = entry.name
        override val kind = "musicfree-plugin"
        override val caps = SourceCaps(platforms = listOf(entry.platform), qualities = listOf("320k", "128k"), supportsSearch = true)
        override val health = SourceHealth(status = "ok")

        override suspend fun search(keyword: String, page: Int): List<Song> {
            val t0 = System.currentTimeMillis()
            return try {
                val (isEnd, items) = host.searchMusic(keyword, page)
                markOk(System.currentTimeMillis() - t0)
                items.mapNotNull { it0 ->
                    val it = it0
                    val name = str(it["title"]) ?: str(it["name"]) ?: return@mapNotNull null
                    val artist = str(it["artist"]) ?: "未知歌手"
                    val songId = str(it["id"]) ?: return@mapNotNull null
                    Song(
                        key = (name + "|" + artist.split("/").first()).lowercase(),
                        name = name, artist = artist,
                        album = str(it["album"]),
                        durationMs = (num(it["duration"])?.times(1000))?.toLong(),
                        origins = listOf(SongOrigin(
                            providerId = id, platform = entry.platform, songId = songId,
                            hash = if (entry.platform == "kg") songId else null,
                            extra = mapOf("__mfItem" to json.encodeToString(
                                kotlinx.serialization.json.JsonElement.serializer(), anyToJsonElement(it)
                            ))
                        )),
                        picUrl = str(it["artwork"])
                    )
                }
            } catch (e: Throwable) {
                markFail(e)
                android.util.Log.w("LisnResolve", "FAIL ${id} search '$keyword' p=$page: ${e.message}")
                emptyList()
            }
        }

        override suspend fun resolveUrl(origin: SongOrigin, quality: String): String {
            val itemJson = origin.extra["__mfItem"] ?: throw RuntimeException("缺少插件歌曲数据(请重新搜索)")
            val item = jsonToJsonNative(json.parseToJsonElement(itemJson)) as? Map<*, *> ?: throw RuntimeException("插件歌曲数据损坏")
            // MusicFree quality: low=128k high=320k super=flac
            val q = when (quality) { "flac" -> "super"; "320k" -> "high"; else -> "low" }
            val t0 = System.currentTimeMillis()
            try {
                val url = host.resolveUrl(item, q)
                markOk(System.currentTimeMillis() - t0)
                return url
            } catch (e: Throwable) { markFail(e); throw e }
        }

        override suspend fun getLyric(origin: SongOrigin): String? {
            val itemJson = origin.extra["__mfItem"] ?: return null
            val item = jsonToJsonNative(json.parseToJsonElement(itemJson)) as? Map<*, *> ?: return null
            return host.getLyric(item)
        }

        override suspend fun test(): Pair<Boolean, String> = try {
            val (isEnd, items) = host.searchMusic("晴天", 1)
            (items.isNotEmpty()) to "搜索返回 ${items.size} 条"
        } catch (e: Throwable) {
            false to (e.message ?: e.toString())
        }
    }
}
