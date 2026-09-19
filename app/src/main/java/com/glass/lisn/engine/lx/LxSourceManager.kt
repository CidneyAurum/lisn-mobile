package com.glass.lisn.engine.lx

import android.content.Context
import android.util.Log
import com.glass.lisn.engine.SourceProvider
import com.glass.lisn.engine.json
import com.glass.lisn.engine.str
import com.glass.lisn.engine.withTimeoutMs
import com.glass.lisn.model.SongOrigin
import com.glass.lisn.model.SourceCaps
import com.glass.lisn.model.SourceHealth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class LxSourceEntry(
    val id: String,
    val name: String,
    val rawUrl: String,
    val jsdelivrUrl: String? = null,
    val repo: String? = null,
    val file: String? = null,
    val branch: String? = null,
    val enabled: Boolean,
    val localVersion: String? = null,
    val remoteDate: String? = null
)

/** 桌面端同款默认 lx 源 */
val DEFAULT_LX_SOURCES = listOf(
    LxSourceEntry("huibq", "Huibq 音源", enabled = true, repo = "Huibq/keep-alive", file = "render_api.js", branch = "master",
        rawUrl = "https://raw.githubusercontent.com/Huibq/keep-alive/master/render_api.js",
        jsdelivrUrl = "https://fastly.jsdelivr.net/gh/Huibq/keep-alive@master/render_api.js"),
    LxSourceEntry("ikun", "ikun 音源", enabled = true, repo = "pdone/lx-music-source", file = "ikun/latest.js", branch = "main",
        rawUrl = "https://raw.githubusercontent.com/pdone/lx-music-source/main/ikun/latest.js",
        jsdelivrUrl = "https://fastly.jsdelivr.net/gh/pdone/lx-music-source@main/ikun/latest.js"),
    LxSourceEntry("qdy", "全豆要聚合音源", enabled = true, repo = "pdone/lx-music-source", file = "qdy/latest.js", branch = "main",
        rawUrl = "https://raw.githubusercontent.com/pdone/lx-music-source/main/qdy/latest.js",
        jsdelivrUrl = "https://fastly.jsdelivr.net/gh/pdone/lx-music-source@main/qdy/latest.js"),
    LxSourceEntry("sixyin", "六音音源", enabled = false, repo = "pdone/lx-music-source", file = "sixyin/latest.js", branch = "main",
        rawUrl = "https://raw.githubusercontent.com/pdone/lx-music-source/main/sixin/latest.js",
        jsdelivrUrl = "https://fastly.jsdelivr.net/gh/pdone/lx-music-source@main/sixin/latest.js")
)

/** lx 源歌曲定位:musicInfo 结构由 origin 组装(kg 用 hash,mg 用 copyrightId) */
fun buildMusicInfo(origin: SongOrigin): Map<String, Any?> {
    val mi = mutableMapOf<String, Any?>("songmid" to origin.songId, "source" to origin.platform)
    if (origin.platform == "kg") mi["hash"] = origin.hash ?: origin.songId
    if (origin.platform == "mg") mi["copyrightId"] = origin.copyrightId ?: origin.songId
    return mi
}

class LxSourceManager(context: Context) {

    private val dir = File(context.filesDir, "lx-sources").apply { mkdirs() }
    private val scriptsDir = File(dir, "scripts").apply { mkdirs() }
    private val backupDir = File(dir, "backup").apply { mkdirs() }
    private val configFile = File(dir, "lx-config.json")

    @Serializable
    data class Persist(val entries: List<LxSourceEntry> = emptyList())

    var entries: List<LxSourceEntry> = emptyList()
        private set
    val hosts = mutableMapOf<String, LxScriptHost>()

    @Synchronized
    fun init() {
        val loaded = runCatching { json.decodeFromString(Persist.serializer(), configFile.readText()).entries }.getOrNull()
        val list = (loaded?.takeIf { it.isNotEmpty() } ?: DEFAULT_LX_SOURCES).toMutableList()
        for (d in DEFAULT_LX_SOURCES) if (list.none { it.id == d.id }) list += d
        entries = list
        save()
    }

    @Synchronized
    private fun save() {
        runCatching { configFile.writeText(json.encodeToString(Persist.serializer(), Persist(entries))) }
    }

    private fun scriptFile(id: String) = File(scriptsDir, "$id.js")

    private suspend fun fetchCode(entry: LxSourceEntry): String = withContext(Dispatchers.IO) {
        var lastErr: Throwable? = null
        for (u in listOfNotNull(entry.rawUrl, entry.jsdelivrUrl)) {
            try {
                val text = com.glass.lisn.engine.httpGetText(u, headers = mapOf("User-Agent" to "LISN-Android/0.1"), timeoutMs = 16000)
                if (text.startsWith("404") || text.length < 40) throw RuntimeException("内容无效")
                return@withContext text
            } catch (e: Throwable) { lastErr = e }
        }
        throw lastErr ?: RuntimeException("下载脚本失败")
    }

    private fun parseMeta(code: String): Pair<String?, String?> {
        val head = code.take(600)
        val name = Regex("@name\\s+([^\\r\\n*]+)").find(head)?.groupValues?.get(1)?.trim()
        val ver = Regex("@version\\s+([^\\r\\n*]+)").find(head)?.groupValues?.get(1)?.trim()
        return name to ver
    }

    suspend fun loadAll(): Pair<List<String>, List<Pair<String, String>>> {
        val ok = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()
        for (entry in entries) {
            if (!entry.enabled) continue
            try {
                val cached = runCatching { scriptFile(entry.id).takeIf { it.length() > 40 }?.readText() }.getOrNull()
                val code = cached ?: fetchCode(entry).also { scriptFile(entry.id).writeText(it) }
                val (metaName, metaVer) = parseMeta(code)
                val host = LxScriptHost(entry.id, metaName ?: entry.name, metaVer)
                host.run(code)
                synchronized(hosts) { hosts[entry.id] = host }
                ok += entry.id
                android.util.Log.i("LisnLx", "lx 源加载成功: ${entry.id} v${metaVer ?: "?"} platforms=${host.sources.keys}")
            } catch (e: Throwable) {
                failed += entry.id to (e.message ?: e.toString())
                android.util.Log.e("LisnLx", "lx 源加载失败: ${entry.id}: ${e.message}")
            }
        }
        return ok to failed
    }

    @Synchronized
    fun setEnabled(id: String, enabled: Boolean) {
        entries = entries.map { if (it.id == id) it.copy(enabled = enabled) else it }
        if (!enabled) synchronized(hosts) { hosts.remove(id)?.close() }
        save()
    }

    fun hostFor(id: String): LxScriptHost? = synchronized(hosts) { hosts[id] }

    fun providerFor(entry: LxSourceEntry): SourceProvider? {
        val host = synchronized(hosts) { hosts[entry.id] } ?: return null
        return LxProvider(entry, host, this)
    }

    class LxProvider(
        private val entry: LxSourceEntry,
        private val host: LxScriptHost,
        private val manager: LxSourceManager
    ) : SourceProvider {
        override val id = "lx:${entry.id}"
        override val name = entry.name
        override val kind = "lx-script"
        override val caps = SourceCaps(
            platforms = host.sources.keys.toList(),
            qualities = host.sources.values.flatMap { it["qualitys"] as? List<*> ?: emptyList() }.mapNotNull { str(it) }.distinct(),
            supportsSearch = false
        )
        override val health = SourceHealth(status = "ok")

        override suspend fun resolveUrl(origin: SongOrigin, quality: String): String {
            val t0 = System.currentTimeMillis()
            try {
                val url = withTimeoutMs(15000) { host.resolveMusicUrl(origin.platform, quality, buildMusicInfo(origin)) }
                markOk(System.currentTimeMillis() - t0)
                return url
            } catch (e: Throwable) {
                markFail(e); throw e
            }
        }

        override suspend fun test(): Pair<Boolean, String> =
            (host.sources.isNotEmpty()) to "已加载,平台: ${host.sources.keys.joinToString("/")} | 音质: ${caps.qualities.joinToString("/")}"
    }

    suspend fun upgrade(id: String): Pair<Boolean, String> {
        val entry = entries.find { it.id == id } ?: return false to "未知音源 $id"
        return try {
            val code = fetchCode(entry)
            runCatching {
                val old = scriptFile(id)
                if (old.exists()) File(backupDir, "$id.${System.currentTimeMillis()}.js").writeText(old.readText())
            }
            scriptFile(id).writeText(code)
            val (metaName, metaVer) = parseMeta(code)
            val host = LxScriptHost(id, metaName ?: entry.name, metaVer)
            host.run(code)
            synchronized(hosts) { hosts[id] = host }
            entries = entries.map { if (it.id == id) it.copy(localVersion = metaVer, remoteDate = java.time.Instant.now().toString()) else it }
            save()
            true to "已升级到 ${metaVer ?: "最新"}"
        } catch (e: Throwable) {
            false to (e.message ?: e.toString())
        }
    }

    /** 添加自定义脚本源:下载 → 解析 @name/@version → 沙箱加载 → 入列表 */
    suspend fun addCustom(name: String, url: String): Pair<Boolean, String> {
        val id = "custom-" + System.currentTimeMillis().toString(36)
        return try {
            val code = withContext(Dispatchers.IO) {
                com.glass.lisn.engine.httpGetText(url, headers = mapOf("User-Agent" to "LISN-Android/0.1"), timeoutMs = 16000)
            }
            if (code.length < 40) throw RuntimeException("脚本内容无效")
            scriptFile(id).writeText(code)
            val (metaName, metaVer) = parseMeta(code)
            val host = LxScriptHost(id, metaName ?: name, metaVer)
            host.run(code)
            synchronized(hosts) { hosts[id] = host }
            entries = entries + LxSourceEntry(
                id = id, name = name.ifEmpty { metaName ?: "自定义源" }, rawUrl = url,
                enabled = true, localVersion = metaVer
            )
            save()
            true to "已添加并加载${metaVer?.let { " (v$it)" } ?: ""}"
        } catch (e: Throwable) {
            false to (e.message ?: e.toString())
        }
    }

    suspend fun checkUpdates() {
        for (entry in entries) {
            if (entry.repo.isNullOrEmpty() || entry.file.isNullOrEmpty() || entry.branch.isNullOrEmpty()) continue
            try {
                val u = "https://api.github.com/repos/${entry.repo}/commits?path=" +
                    java.net.URLEncoder.encode(entry.file, "UTF-8") + "&sha=" + entry.branch + "&per_page=1"
                val text = com.glass.lisn.engine.httpGetText(u, headers = mapOf("Accept" to "application/vnd.github+json"), timeoutMs = 10000)
                val arr = com.glass.lisn.engine.jsonToJsonNative(json.parseToJsonElement(text)) as? List<*> ?: continue
                val first = arr.firstOrNull() as? Map<*, *> ?: continue
                val commit = first["commit"] as? Map<*, *> ?: continue
                val date = str(commit["committer"]?.let { (it as? Map<*, *>)?.get("date") })
                    ?: str(commit["author"]?.let { (it as? Map<*, *>)?.get("date") }) ?: continue
                entries = entries.map { if (it.id == entry.id) it.copy(remoteDate = date) else it }
            } catch (_: Throwable) { /* 下次再查 */ }
        }
        save()
    }
}
