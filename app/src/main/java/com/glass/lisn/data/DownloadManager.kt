package com.glass.lisn.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import com.glass.lisn.engine.ResolveResult
import com.glass.lisn.engine.SourceRegistry
import com.glass.lisn.engine.http
import com.glass.lisn.model.DownloadItem
import com.glass.lisn.model.DownloadStatus
import com.glass.lisn.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.app.NotificationCompat
import okhttp3.Request
import java.io.File

/** 下载管理:解析 → 下载 → 写入媒体库(MediaStore),状态流驱动 UI */
class DownloadManager(private val context: Context, private val registry: () -> SourceRegistry) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _queue = MutableStateFlow<List<DownloadItem>>(emptyList())
    val queue: StateFlow<List<DownloadItem>> = _queue
    private val jobs = mutableMapOf<String, Job>()
    private val songsById = mutableMapOf<String, Song>()

    private fun ensureChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("download", "下载", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    private fun notifyDone(item: DownloadItem, ok: Boolean, detail: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(nm)
        val title = if (ok) "下载完成" else "下载失败"
        val text = "${item.artist} - ${item.name} · ${item.quality.uppercase()}" + (if (!ok) " ($detail)" else "")
        val n = NotificationCompat.Builder(context, "download")
            .setSmallIcon(if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(title).setContentText(text)
            .setAutoCancel(true).build()
        runCatching { nm.notify(item.id.hashCode(), n) }
    }

    private fun patch(id: String, transform: (DownloadItem) -> DownloadItem) {
        _queue.value = _queue.value.map { if (it.id == id) transform(it) else it }
    }

    fun enqueue(song: Song, quality: String): DownloadItem {
        val item = DownloadItem(
            id = "dl-${System.currentTimeMillis().toString(36)}-${(0..46655).random().toString(36)}",
            name = song.name,
            artist = song.artist,
            album = song.album,
            quality = quality,
            status = DownloadStatus.WAITING,
            createdAt = System.currentTimeMillis()
        )
        _queue.value = listOf(item) + _queue.value
        synchronized(songsById) { songsById[item.id] = song }
        jobs[item.id] = scope.launch { run(item.id, song, quality) }
        return item
    }

    private suspend fun run(id: String, song: Song, quality: String) {
        try {
            patch(id) { it.copy(status = DownloadStatus.RESOLVING) }
            val res: ResolveResult = registry().resolveUrl(song, quality)
            patch(id) { it.copy(status = DownloadStatus.DOWNLOADING, sourceId = res.providerId, platform = res.platform) }
            val ext = if (res.quality == "flac") "flac" else "mp3"
            val safeName = "${song.artist.take(60).replace(Regex("[\\\\/:*?\"<>|]"), "_")} - ${song.name.take(80).replace(Regex("[\\\\/:*?\"<>|]"), "_")}.$ext"
            val saved = downloadToLibrary(res.url, safeName, song) { received, total ->
                patch(id) {
                    it.copy(
                        received = received, total = total,
                        progress = if (total > 0) received.toFloat() / total else it.progress
                    )
                }
            }
            patch(id) { it.copy(status = DownloadStatus.COMPLETED, progress = 1f, filePath = saved) }
            notifyDone(_queue.value.find { it.id == id } ?: DownloadItem(id = id, name = song.name, artist = song.artist, quality = quality), true, "")
        } catch (e: kotlinx.coroutines.CancellationException) {
            patch(id) { it.copy(status = DownloadStatus.CANCELLED) }
        } catch (e: Throwable) {
            patch(id) { it.copy(status = DownloadStatus.FAILED, error = e.message ?: e.toString()) }
            notifyDone(_queue.value.find { it.id == id } ?: DownloadItem(id = id, name = song.name, artist = song.artist, quality = quality), false, e.message ?: e.toString())
        } finally {
            jobs.remove(id)
        }
    }

    private suspend fun downloadToLibrary(
        url: String, fileName: String, song: Song, onProgress: (Long, Long) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val client = http.newBuilder().build()
        val req = Request.Builder().url(url)
            .header("User-Agent", com.glass.lisn.engine.UA).build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw RuntimeException("下载 HTTP ${res.code}")
            val body = res.body ?: throw RuntimeException("空响应体")
            val total = body.contentLength()

            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Audio.Media.MIME_TYPE, if (fileName.endsWith(".flac")) "audio/flac" else "audio/mpeg")
                    if (total > 0) put(MediaStore.Audio.Media.SIZE, total)
                    put(MediaStore.Audio.Media.TITLE, song.name)
                    put(MediaStore.Audio.Media.ARTIST, song.artist.split("/").first())
                    song.album?.let { put(MediaStore.Audio.Media.ALBUM, it) }
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/LISN")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw RuntimeException("无法创建媒体文件")
                resolver.openOutputStream(uri)?.use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        var received = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            received += n
                            onProgress(received, if (total > 0) total else received)
                        }
                    }
                } ?: throw RuntimeException("无法打开输出流")
                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri.toString()
            } else {
                val dir = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC), "LISN").apply { mkdirs() }
                val f = File(dir, fileName)
                body.byteStream().use { input ->
                    f.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var received = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            received += n
                            onProgress(received, if (total > 0) total else received)
                        }
                    }
                }
                f.absolutePath
            }
        }
    }

    fun cancel(id: String) { jobs[id]?.cancel() }

    fun retry(id: String) {
        val item = _queue.value.find { it.id == id } ?: return
        val song = synchronized(songsById) { songsById[id] } ?: return
        remove(id)
        enqueue(song, item.quality)
    }

    fun remove(id: String) {
        jobs[id]?.cancel()
        synchronized(songsById) { songsById.remove(id) }
        _queue.value = _queue.value.filter { it.id != id }
    }

    fun clearFinished() {
        _queue.value = _queue.value.filter {
            it.status == DownloadStatus.WAITING || it.status == DownloadStatus.RESOLVING || it.status == DownloadStatus.DOWNLOADING
        }
    }

    companion object {
        /** 扫描媒体库中的 LISN 下载目录 */
        suspend fun scanLibrary(context: Context): List<com.glass.lisn.model.LibraryFile> =
            withContext(Dispatchers.IO) {
                val out = mutableListOf<com.glass.lisn.model.LibraryFile>()
                val resolver = context.contentResolver
                val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                val proj = arrayOf(
                    MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DATE_MODIFIED,
                    MediaStore.Audio.Media.RELATIVE_PATH
                )
                val sel = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
                try {
                    resolver.query(collection, proj, sel, arrayOf("%Music/LISN%"), "${MediaStore.Audio.Media.DATE_MODIFIED} DESC")?.use { c ->
                        while (c.moveToNext()) {
                            val display = c.getString(1) ?: continue
                            val m = Regex("^(.+) - (.+)\\.(mp3|flac)$", RegexOption.IGNORE_CASE).find(display) ?: continue
                            val uri = android.content.ContentUris.withAppendedId(collection, c.getLong(0))
                            out += com.glass.lisn.model.LibraryFile(
                                uri = uri.toString(),
                                name = m.groupValues[2],
                                artist = m.groupValues[1],
                                isFlac = m.groupValues[3].equals("flac", true),
                                size = c.getLong(4),
                                mtime = c.getLong(5) * 1000
                            )
                        }
                    }
                } catch (_: Throwable) { /* 无权限 */ }
                out
            }
    }
}
