@file:OptIn(com.dokar.quickjs.ExperimentalQuickJsApi::class)

package com.glass.lisn.engine.lx

import android.util.Log
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import com.glass.lisn.engine.UA
import com.glass.lisn.engine.anyToJsonElement
import com.glass.lisn.engine.http
import com.glass.lisn.engine.json
import com.glass.lisn.engine.jsonToJsonNative
import com.glass.lisn.engine.num
import com.glass.lisn.engine.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * lx-music 自定义源协议宿主(QuickJS)。
 * 注入全局 lx 对象(EVENT_NAMES/on/send/request/utils/env/version/currentScriptInfo),
 * utils.crypto/zlib 由 Kotlin 实现,Buffer 以 hex/base64 字符串跨界(规避引擎类型转换边界)。
 */
class LxScriptHost(private val id: String, private val name: String, private val version: String? = null) : AutoCloseable {

    private val qjs: QuickJs = QuickJs.create(Dispatchers.IO)

    @Volatile private var requestHandlerInstalled = false
    @Volatile var inited: Boolean = false
        private set
    var sources: Map<String, Map<String, Any?>> = emptyMap()
        private set

    suspend fun run(code: String) {
        installBindings()
        qjs.evaluate<Any?>(LxPrelude.PRELUDE, filename = "lx-shims.js")
        qjs.evaluate<Any?>(LxPrelude.LX_GLOBAL_JS, filename = "lx-global.js")
        qjs.evaluate<Any?>(code, filename = "lxsource-$id.js")
        waitInited(6000)
    }

    @Synchronized
    private fun onInited(data: Map<*, *>?) {
        @Suppress("UNCHECKED_CAST")
        sources = (data?.get("sources") as? Map<String, Map<String, Any?>>) ?: emptyMap()
        inited = true
    }

    suspend fun waitInited(ms: Long) {
        val start = System.currentTimeMillis()
        while (!inited) {
            if (System.currentTimeMillis() - start > ms) throw RuntimeException("脚本初始化超时(${ms}ms): $name")
            delay(100)
        }
    }

    /** 解析 VIP 曲 URL:调用脚本注册的 request 处理器(顶层 await 取值) */
    suspend fun resolveMusicUrl(source: String, quality: String, musicInfo: Map<*, *>): String {
        if (!requestHandlerInstalled) throw RuntimeException("脚本未注册 request 处理器: $name")
        val mi = Json.encodeToString(JsonElement.serializer(), anyToJsonElement(musicInfo))
        val url = qjs.evaluate<String>(
            "await (async () => globalThis.__lxHandle({ source: '${jsStr(source)}', action: 'musicUrl', info: { type: '${jsStr(quality)}', musicInfo: $mi } }))()"
        )
        if (url.isNullOrEmpty() || !url.startsWith("http")) throw RuntimeException("脚本返回非 URL: $name")
        return url
    }

    private fun jsStr(s: String): String = s.replace("\\", "\\\\").replace("'", "\\'")

    override fun close() {
        runCatching { qjs.close() }
    }

    // ---------- 宿主原生绑定 ----------
    private fun installBindings() {
        qjs.function("__lxLog") { args ->
            Log.d("lx:$id".take(23), args.joinToString(" ") { str(it) }); null
        }
        qjs.asyncFunction("__lxRequestAsync") { args ->
            val cfg = args.firstOrNull() as? Map<*, *> ?: emptyMap<Any?, Any?>()
            @Suppress("UNCHECKED_CAST")
            lxHttpRequest(cfg as Map<String, Any?>)
        }
        qjs.function("__lxAes") { args ->
            lxLadderAes(str(args[0]), str(args[1]), str(args[2]), str(args.getOrNull(3) ?: ""), str(args.getOrNull(4) ?: "1") != "0")
        }
        qjs.function("__lxMd5") { args -> md5Hex(str(args[0])) }
        qjs.function("__lxRandomBytes") { args ->
            val n = (num(args[0]) ?: 16.0).toInt().coerceIn(1, 1024)
            val b = ByteArray(n); SecureRandom().nextBytes(b); b.toHex()
        }
        qjs.function("__lxRsaEncrypt") { args -> lxRsaPublicEncrypt(str(args[0]), str(args[1])) }
        qjs.function("__lxZlibInflate") { args -> zlib(str(args[0]), inflate = true, raw = false) }
        qjs.function("__lxZlibDeflate") { args -> zlib(str(args[0]), inflate = false, raw = false) }
        qjs.function("__lxZlibInflateRaw") { args -> zlib(str(args[0]), inflate = true, raw = true) }
        qjs.function("__lxBytesFromUtf8") { args -> str(args[0]).toByteArray(Charsets.UTF_8).toHex() }
        qjs.function("__lxBytesToUtf8") { args -> str(args[0]).hexToBytes().toString(Charsets.UTF_8) }
        qjs.function("__lxB64FromBytes") { args ->
            val list = args[0] as? List<*> ?: emptyList<Any?>()
            Base64.getEncoder().encodeToString(list.map { (num(it) ?: 0.0).toInt().toByte() }.toByteArray())
        }
        qjs.function("__lxB64ToBytes") { args ->
            Base64.getDecoder().decode(fixB64(str(args[0]))).map { it.toInt() and 0xFF }
        }
        qjs.function("__lxOnInited") { args ->
            onInited(args.firstOrNull() as? Map<*, *>); null
        }
        qjs.function("__lxMarkHandler") { _ -> requestHandlerInstalled = true; null }
        qjs.function(com.glass.lisn.engine.lx.LxPrelude.META_FN) { listOf(id, name, version ?: "") }
        qjs.asyncFunction("__lxDelay") { args ->
            delay((num(args.firstOrNull()) ?: 0.0).toLong().coerceIn(0, 60_000)); null
        }
        qjs.function("__lxHexToB64") { args -> Base64.getEncoder().encodeToString(str(args[0]).hexToBytes()) }
        qjs.function("__lxB64ToHex") { args -> Base64.getDecoder().decode(fixB64(str(args[0]))).toHex() }
    }

    // ---------- 原语实现 ----------
    private fun str(v: Any?): String = when (v) {
        null -> ""
        is String -> v
        is Double -> if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
        is Number -> v.toString()
        is Boolean -> v.toString()
        else -> v.toString()
    }

    private fun num(v: Any?): Double? = when (v) {
        null -> null
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }

    private fun fixB64(s: String): String {
        val clean = s.trim().replace("\n", "").replace("\r", "")
        return clean + "=".repeat((4 - clean.length % 4) % 4)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    private fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private fun lxLadderAes(dataHex: String, transform: String, keyHex: String, ivHex: String, encrypt: Boolean): String {
        // 归一化 'aes-128-cbc' / 'AES/CBC/PKCS5Padding' 等 Java transform
        val t = transform.trim()
        val norm = if (t.contains('/')) {
            val parts = t.split('/')
            listOf(parts[0].uppercase(), parts[1].uppercase(), parts[2]).joinToString("/")
                .replace("PKCS7", "PKCS5Padding").replace("PKCS5PADDING", "PKCS5Padding")
        } else {
            val parts = t.lowercase().split("-")
            require(parts.size >= 2 && parts[0] == "aes") { "不支持的 AES transform: $t" }
            val mode = parts[1].uppercase()
            val padding = parts.getOrNull(2)?.uppercase()?.let { if (it == "PKCS7" || it == "PKCS5") "PKCS5Padding" else it } ?: "PKCS5Padding"
            "AES/${mode}/$padding"
        }
        val keyBytes = keyHex.hexToBytes()
        require(keyBytes.size in intArrayOf(16, 24, 32)) { "AES key 长度非法: ${keyBytes.size}" }
        val cipher = Cipher.getInstance(norm)
        val keySpec = SecretKeySpec(keyBytes, "AES")
        if (ivHex.isEmpty() || norm.contains("ECB")) cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, keySpec)
        else cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(ivHex.hexToBytes()))
        return cipher.doFinal(dataHex.hexToBytes()).toHex()
    }

    private fun lxRsaPublicEncrypt(dataHex: String, pemOrHex: String): String {
        val pem = if (pemOrHex.contains("-----BEGIN")) pemOrHex
        else "-----BEGIN PUBLIC KEY-----\n" + pemOrHex.chunked(64).joinToString("\n") + "\n-----END PUBLIC KEY-----"
        val b64 = pem.substringAfter("-----BEGIN PUBLIC KEY-----").substringBefore("-----END")
            .replace("\n", "").replace("\r", "").trim()
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(b64)))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.doFinal(dataHex.hexToBytes()).toHex()
    }

    private fun zlib(hex: String, inflate: Boolean, raw: Boolean): String {
        return if (inflate) {
            val inflater = Inflater(raw)
            inflater.setInput(hex.hexToBytes())
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && inflater.needsInput()) break
                out.write(buf, 0, n)
            }
            inflater.end()
            out.toByteArray().toHex()
        } else {
            val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, raw)
            deflater.setInput(hex.hexToBytes())
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
            deflater.end()
            out.toByteArray().toHex()
        }
    }

    private fun md5Hex(s: String): String =
        java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)).toHex()

    private fun lxHttpRequest(cfg: Map<String, Any?>): Map<String, Any?> {
        var url = str(cfg["url"])
        val method = (str(cfg["method"]).ifEmpty { "GET" }).uppercase()
        val headers = linkedMapOf("User-Agent" to UA)
        (cfg["headers"] as? Map<*, *>)?.forEach { (k, v) ->
            str(k).takeIf { it.isNotEmpty() }?.let { key -> str(v).takeIf { it.isNotEmpty() }?.let { headers[key] = it } }
        }
        var body: String? = null
        val data = cfg["body"]
        if (data != null && method != "GET" && method != "HEAD") {
            body = if (data is String) data else {
                if (!headers.containsKey("Content-Type")) headers["Content-Type"] = "application/json"
                Json.encodeToString(JsonElement.serializer(), anyToJsonElement(data))
            }
        }
        (cfg["form"] as? Map<*, *>)?.takeIf { it.isNotEmpty() }?.let { form ->
            body = form.entries.filter { it.value != null }
                .joinToString("&") { java.net.URLEncoder.encode(str(it.key), "UTF-8") + "=" + java.net.URLEncoder.encode(str(it.value), "UTF-8") }
            if (!headers.containsKey("Content-Type")) headers["Content-Type"] = "application/x-www-form-urlencoded"
        }
        val timeout = (num(cfg["timeout"]) ?: 15000.0).toLong().coerceIn(2000, 60000)
        val builder = Request.Builder().url(url).header("User-Agent", UA)
        for ((k, v) in headers) builder.header(k, v)
        if (body != null) builder.method(method, body.toRequestBody((headers["Content-Type"] ?: "application/json").toMediaType()))
        val client = http.newBuilder().callTimeout(timeout + 3000, TimeUnit.MILLISECONDS).build()
        return client.newCall(builder.build()).execute().use { res ->
            val text = res.body?.string() ?: ""
            val hdrs = linkedMapOf<String, String>()
            for ((k, v) in res.headers.toMultimap()) hdrs[k.lowercase()] = v.firstOrNull() ?: ""
            val parsed: Any? = runCatching { jsonToJsonNative(json.parseToJsonElement(text)) }.getOrDefault(text)
            linkedMapOf("statusCode" to res.code, "headers" to hdrs, "body" to parsed)
        }
    }
}
