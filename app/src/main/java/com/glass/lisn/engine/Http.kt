package com.glass.lisn.engine

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
import kotlin.random.Random

/** 桌面端与插件共用的浏览器 UA */
const val UA: String =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    explicitNulls = true
}

/** 全局 OkHttp:插件网络、音源 API、下载共用连接池 */
val http: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
}

fun httpGetText(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Long = 15000): String {
    val b = Request.Builder().url(url).header("User-Agent", UA)
    for ((k, v) in headers) b.header(k, v)
    val client = http.newBuilder().callTimeout(timeoutMs + 2000, TimeUnit.MILLISECONDS).build()
    client.newCall(b.build()).execute().use { res ->
        val body = res.body?.string() ?: ""
        if (!res.isSuccessful) throw RuntimeException("HTTP ${res.code}: ${body.take(80)}")
        return body
    }
}

/** 通用 HTTP 请求(axios 垫片后端)。返回 JSON 字符串(避免 JS 引擎类型转换的边界问题)。 */
suspend fun httpRequestShim(cfg: Map<String, Any?>): String {
    var url = str(cfg["url"]) ?: throw RuntimeException("axios: 缺少 url")
    val method = (str(cfg["method"]) ?: "get").lowercase()
    val headers = mutableMapOf("User-Agent" to UA)
    (cfg["headers"] as? Map<*, *>)?.forEach { (k, v) ->
        str(v)?.let { headers[str(k)?.lowercase() ?: return@forEach] = it }
    }
    var body: String? = null
    val data = cfg["data"]
    if (data != null && method != "get" && method != "head") {
        if (data is String) body = data
        else {
            body = json.encodeToString(JsonElement.serializer(), anyToJsonElement(data))
            if (!headers.containsKey("content-type")) headers["Content-Type"] = "application/json"
        }
    }
    (cfg["params"] as? Map<*, *>)?.takeIf { it.isNotEmpty() }?.let { params ->
        val qs = params.entries.filter { it.value != null }
            .joinToString("&") { "${enc(str(it.key) ?: "")}=${enc(str(it.value) ?: "")}" }
        url += (if (url.contains('?')) "&" else "?") + qs
    }
    val timeout = (num(cfg["timeout"]) ?: 15000.0).toLong().coerceIn(2000, 30000)
    val b = Request.Builder().url(url).header("User-Agent", UA)
    for ((k, v) in headers) b.header(k, v)
    if (body != null) {
        val ct = headers["Content-Type"] ?: "application/json"
        b.method(method.uppercase(), body.toRequestBody(ct.toMediaType()))
    }
    val client = http.newBuilder().callTimeout(timeout + 3000, TimeUnit.MILLISECONDS).build()
    val resp: Map<String, Any?> = client.newCall(b.build()).execute().use { res ->
        val text = res.body?.string() ?: ""
        val hdrs = mutableMapOf<String, String>()
        for ((k, v) in res.headers.toMultimap()) hdrs[k.lowercase()] = v.firstOrNull() ?: ""
        val ct = hdrs["content-type"] ?: ""
        val responseType = str(cfg["responseType"])
        val parsed: Any? = when {
            responseType == "text" -> text
            ct.contains("json") || text.trimStart().startsWith("{") || text.trimStart().startsWith("[") ->
                runCatching { jsonToJsonNative(json.parseToJsonElement(text)) }.getOrDefault(text)
            else -> text
        }
        mapOf(
            "data" to parsed,
            "status" to res.code,
            "statusText" to res.message,
            "headers" to hdrs
        )
    }
    return json.encodeToString(JsonElement.serializer(), anyToJsonElement(resp))
}

fun enc(s: String): String =
    java.net.URLEncoder.encode(s, "UTF-8")

fun str(v: Any?): String? = when (v) {
    null -> null
    is String -> v
    is Number, is Boolean -> v.toString()
    else -> v.toString()
}

fun num(v: Any?): Double? = when (v) {
    null -> null
    is Number -> v.toDouble()
    is String -> v.toDoubleOrNull()
    else -> null
}

// ---------- JS 值转换 ----------

fun jsonToJsonNative(e: JsonElement): Any? = when {
    e is kotlinx.serialization.json.JsonNull -> null
    e is kotlinx.serialization.json.JsonPrimitive -> when {
        e.isString -> e.content
        e.booleanOrNull != null -> e.boolean
        else -> e.content.toDoubleOrNull() ?: e.content
    }
    e is kotlinx.serialization.json.JsonArray -> e.map { jsonToJsonNative(it) }
    e is kotlinx.serialization.json.JsonObject -> e.entries.associate { it.key to jsonToJsonNative(it.value) }
    else -> null
}

fun anyToJsonElement(v: Any?): JsonElement = when (v) {
    null -> kotlinx.serialization.json.JsonNull
    is JsonElement -> v
    is String -> kotlinx.serialization.json.JsonPrimitive(v)
    is Number -> kotlinx.serialization.json.JsonPrimitive(v)
    is Boolean -> kotlinx.serialization.json.JsonPrimitive(v)
    is Map<*, *> -> kotlinx.serialization.json.buildJsonObject {
        v.forEach { (k, value) -> put(str(k) ?: "", anyToJsonElement(value)) }
    }
    is List<*> -> kotlinx.serialization.json.buildJsonArray {
        v.forEach { add(anyToJsonElement(it)) }
    }
    else -> kotlinx.serialization.json.JsonPrimitive(v.toString())
}

// ---------- crypto 垫片 ----------

private fun digestHex(alg: String, data: ByteArray): String =
    MessageDigest.getInstance(alg).digest(data).joinToString("") { "%02x".format(it) }

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
private fun String.unhex(): ByteArray = ByteArray(length / 2) { i ->
    substring(i * 2, i * 2 + 2).toInt(16).toByte()
}

fun md5Hex(s: String): String = digestHex("MD5", s.toByteArray())
fun sha1Hex(s: String): String = digestHex("SHA-1", s.toByteArray())
fun sha256Hex(s: String): String = digestHex("SHA-256", s.toByteArray())
fun utf8ToHex(s: String): String = s.toByteArray(Charsets.UTF_8).hex()
fun latin1ToHex(s: String): String = s.map { (it.code and 0xFF).toByte() }.toByteArray().hex()
fun b64ToHex(s: String): String = Base64.getDecoder().decode(fixB64(s)).hex()
fun hexToB64(hex: String): String = Base64.getEncoder().encodeToString(hex.unhex())
fun hexToUtf8(hex: String): String = String(hex.unhex(), Charsets.UTF_8)

private fun fixB64(s: String): String {
    val clean = s.trim().replace("\n", "").replace("\r", "")
    val pad = (4 - clean.length % 4) % 4
    return clean + "=".repeat(pad)
}

/** crypto-js 语义:AES CBC/ECB + PKCS7,输出 Base64(即 CipherParams.toString()) */
fun aesEncryptHexToB64(dataHex: String, keyHex: String, ivHex: String?, mode: String): String {
    val keyLen = keyHex.length / 2
    require(keyLen == 16 || keyLen == 24 || keyLen == 32) { "AES key 长度非法: $keyLen" }
    val cipher = Cipher.getInstance(if (mode.equals("ECB", true)) "AES/ECB/PKCS5Padding" else "AES/CBC/PKCS5Padding")
    val keySpec = SecretKeySpec(keyHex.unhex(), "AES")
    if (mode.equals("ECB", true)) cipher.init(Cipher.ENCRYPT_MODE, keySpec)
    else cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(ivHex!!.unhex()))
    return Base64.getEncoder().encodeToString(cipher.doFinal(dataHex.unhex()))
}

// ---------- big-integer 垫片 ----------

fun bigStrToHex(s: String, radix: Int): String = BigInteger(s, radix).toString(16)
fun bigNumToHex(v: Double): String = java.math.BigDecimal(v).toBigInteger().toString(16)
fun bigModPow(aHex: String, eHex: String, mHex: String): String = BigInteger(aHex, 16).modPow(BigInteger(eHex, 16), BigInteger(mHex, 16)).toString(16)
fun bigToString(hex: String, radix: Int): String = BigInteger(hex, 16).toString(radix)
fun bigMultiply(aHex: String, bHex: String): String = BigInteger(aHex, 16).multiply(BigInteger(bHex, 16)).toString(16)
fun bigMod(aHex: String, bHex: String): String = BigInteger(aHex, 16).mod(BigInteger(bHex, 16)).toString(16)

private typealias BigInteger = java.math.BigInteger

// ---------- cheerio 垫片后端:jsoup → JSON 树 ----------

fun htmlTreeJson(html: String): String {
    val tree = htmlTree(html)
    return json.encodeToString(JsonElement.serializer(), anyToJsonElement(tree))
}

fun htmlTree(html: String): List<Map<String, Any?>> {
    val doc = Jsoup.parse(html)
    return doc.body().childNodes().mapNotNull { nodeToMap(it) }.filter { it.isNotEmpty() }
}

private fun nodeToMap(n: Node): Map<String, Any?>? {
    if (n is TextNode) return null
    if (n !is Element) return null
    val attrs = mutableMapOf<String, Any?>()
    for (a in n.attributes()) attrs[a.key.lowercase()] = a.value
    val children = n.childNodes().mapNotNull { nodeToMap(it) }.filter { it.isNotEmpty() }
    return mapOf(
        "tag" to n.tagName().lowercase(),
        "attrs" to attrs,
        "children" to children,
        "text" to n.text()
    )
}

// ---------- 其它 ----------

/** 稳定日期哈希 → 每日推荐关键词(与桌面端一致的池子) */
fun dailyKeywords(nowMs: Long = System.currentTimeMillis()): List<String> {
    val pool = listOf("周杰伦", "林俊杰", "陈奕迅", "邓紫棋", "薛之谦", "五月天", "告五人", "房东的猫", "毛不易", "新裤子", "回春丹", "泽野弘之", "YOASOBI", "Aimer", "RADWIMPS", "米津玄师")
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = nowMs }
    val seed = cal.get(java.util.Calendar.YEAR) * 10000 + (cal.get(java.util.Calendar.MONTH) + 1) * 100 + cal.get(java.util.Calendar.DAY_OF_MONTH)
    val a = pool[seed % pool.size]
    val b = pool[abs((seed * 7 + 3) % pool.size)]
    return (listOf(a, b)).distinct()
}

fun randomHex16(): String {
    val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    var c = ""
    repeat(16) { c += chars[Random.nextInt(chars.length)] }
    return c
}

fun dbg(tag: String, msg: String) = Log.d(tag, msg)
