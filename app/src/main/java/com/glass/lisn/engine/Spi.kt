package com.glass.lisn.engine

import com.glass.lisn.model.Song
import com.glass.lisn.model.SongOrigin
import com.glass.lisn.model.SourceCaps
import com.glass.lisn.model.SourceHealth

/** 音源解析失败:code 区分全部失败 / 手动锁定失败 */
class ResolveError(message: String, val code: String = "all-failed") : Exception(message)

data class ResolveResult(
    val url: String,
    val providerId: String,
    val platform: String,
    val quality: String
)

/** 音源 Provider 接口 —— 所有 kind 的最终形态(kind: gd / musicfree-plugin / http-api) */
interface SourceProvider {
    val id: String
    val name: String
    val kind: String
    val caps: SourceCaps
    val health: SourceHealth

    suspend fun search(keyword: String, page: Int): List<Song> = emptyList()
    suspend fun resolveUrl(origin: SongOrigin, quality: String): String
    suspend fun getPic(origin: SongOrigin): String? = null
    suspend fun getLyric(origin: SongOrigin): String? = null
    suspend fun test(): Pair<Boolean, String> =
        (health.status == "ok") to (health.lastError ?: "已就绪")

    fun markOk(latencyMs: Long) {
        health.status = "ok"; health.latencyMs = latencyMs; health.okCount++
        health.lastOkAt = System.currentTimeMillis(); health.lastError = null
    }

    fun markFail(err: Throwable) {
        health.failCount++
        health.lastError = err.message ?: err.toString()
        if (health.status != "disabled") health.status = "error"
    }
}

/** 质量降级链(与桌面端一致) */
val QUALITY_CHAIN: Map<String, List<String>> = mapOf(
    "flac" to listOf("flac", "320k", "128k"),
    "320k" to listOf("320k", "128k"),
    "128k" to listOf("128k", "320k")
)

suspend fun <T> withTimeoutMs(ms: Long, label: String = "timeout", block: suspend () -> T): T =
    kotlinx.coroutines.withTimeout(ms) { block() }
