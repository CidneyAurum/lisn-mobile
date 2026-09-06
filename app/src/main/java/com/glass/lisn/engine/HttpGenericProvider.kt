package com.glass.lisn.engine

import com.glass.lisn.model.SongOrigin
import com.glass.lisn.model.SourceCaps
import com.glass.lisn.model.SourceHealth
import kotlinx.serialization.Serializable

/**
 * 通用 HTTP 音源模板 —— 零代码接入形态。
 * 只需配置 URL 模板 + 鉴权头 + 响应 JSON 路径,即可接入任何
 * 形如 /url/{source}/{songId}/{quality} 的音源后端(如 Huibq 后端)。
 */
@Serializable
data class HttpSourceConfig(
    val id: String,
    val name: String,
    val urlTemplate: String,
    val headers: Map<String, String> = emptyMap(),
    val jsonPath: String = "url",
    val platforms: List<String>,
    val qualities: List<String> = listOf("320k", "128k"),
    val enabled: Boolean? = true
)

class HttpGenericProvider(val config: HttpSourceConfig) : SourceProvider {
    override val id = config.id
    override val name = config.name
    override val kind = "http-api"
    override val caps = SourceCaps(platforms = config.platforms, qualities = config.qualities, supportsSearch = false)
    override val health = SourceHealth(status = if (config.enabled == false) "disabled" else "loading")

    override suspend fun resolveUrl(origin: SongOrigin, quality: String): String {
        val url = config.urlTemplate
            .replace("{source}", origin.platform)
            .replace("{songId}", origin.hash ?: origin.songId)
            .replace("{quality}", quality)
        val t0 = System.currentTimeMillis()
        try {
            val text = withTimeoutMs(15000, "HTTP 音源超时") {
                httpGetText(url, headers = config.headers, timeoutMs = 15000)
            }
            val textOut: Any? = runCatching { jsonToJsonNative(json.parseToJsonElement(text)) }.getOrDefault(text)
            var cur: Any? = textOut
            for (k in config.jsonPath.split('.')) cur = (cur as? Map<*, *>)?.get(k)
            val out = if (cur is String && textOut is String) textOut else str(cur) ?: text
            if (!out.startsWith("http")) { markFail(RuntimeException("无可用 URL")); throw RuntimeException("响应中无可用 URL") }
            markOk(System.currentTimeMillis() - t0)
            return out
        } catch (e: Exception) {
            markFail(e); throw e
        }
    }

    override suspend fun test(): Pair<Boolean, String> =
        (health.status == "ok") to (health.lastError ?: "已就绪(等待首次解析验证)")
}
