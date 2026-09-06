@file:OptIn(com.dokar.quickjs.ExperimentalQuickJsApi::class)

package com.glass.lisn.engine

import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import java.io.Closeable

/**
 * MusicFree 插件沙箱宿主 —— 基于 QuickJS(quickjs-kt)。
 * 插件脚本以 CommonJS 形式运行,require 由 MfShims 提供。
 */
class MusicFreeHost(private val id: String, private val name: String) : Closeable {

    private val qjs: QuickJs = QuickJs.create(Dispatchers.IO)
    private var pluginPlatform: String? = null
    var version: String? = null
        private set

    suspend fun load(code: String) {
        MfShims.install(qjs, id)
        qjs.evaluate<Any?>(MfShims.PRELUDE, filename = "shims.js")
        val wrapped = "(function(){ var module = { exports: {} }; var exports = module.exports;\n" +
            code + "\n;globalThis.__plugin = module.exports; })()"
        qjs.evaluate<Any?>(wrapped, filename = "musicfree-$id.js")
        val p = qjs.evaluate<Map<String, Any?>>("globalThis.__plugin")
            ?: throw RuntimeException("插件导出为空")
        pluginPlatform = str(p["platform"]) ?: throw RuntimeException("插件未导出 platform")
        version = str(p["version"])
    }

    /** 返回 isEnd 与条目列表 */
    suspend fun searchMusic(keyword: String, page: Int): Pair<Boolean, List<Map<*, *>>> {
        val kw = Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(keyword))
        val r = qjs.evaluate<Map<String, Any?>>(
            "await globalThis.__plugin.search($kw, $page, 'music')"
        ) ?: throw RuntimeException("搜索返回结构异常")
        val data = r["data"] as? List<*> ?: throw RuntimeException("搜索返回 data 非数组")
        val isEnd = r["isEnd"] == true
        return isEnd to data.filterIsInstance<Map<*, *>>()
    }

    suspend fun resolveUrl(musicItem: Map<*, *>, quality: String): String {
        val itemJson = Json.encodeToString(
            kotlinx.serialization.json.JsonElement.serializer(), anyToJsonElement(musicItem)
        )
        val r = qjs.evaluate<Map<String, Any?>>(
            "await globalThis.__plugin.getMediaSource($itemJson, '$quality')"
        ) ?: throw RuntimeException("插件未返回结果")
        val url = str(r["url"])
        if (url.isNullOrEmpty() || !url.startsWith("http")) throw RuntimeException("插件未返回可用 URL")
        return url
    }

    suspend fun getLyric(musicItem: Map<*, *>): String? = try {
        val itemJson = Json.encodeToString(
            kotlinx.serialization.json.JsonElement.serializer(), anyToJsonElement(musicItem)
        )
        val r = qjs.evaluate<Map<String, Any?>>(
            "await globalThis.__plugin.getLyric($itemJson)"
        )
        str(r?.get("rawLrc"))
    } catch (e: Throwable) {
        android.util.Log.e("LisnMf", "getLyric 失败: ${e.message}")
        null
    }

    override fun close() {
        runCatching { qjs.close() }
    }
}

class MusicFreePluginException(message: String) : RuntimeException(message)
