package com.glass.lisn.ui.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import com.glass.lisn.EngineHub
import com.glass.lisn.model.Song
import com.glass.lisn.engine.LrcParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 桌面歌词悬浮窗管理:SYSTEM_ALERT_WINDOW + 全穿透。
 *  由 PlaybackService 直驱:onSongChanged / onPos,无二次 MediaController 链路。 */
object DesktopLyricsOverlay {

    private var view: LimbusOverlayView? = null
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var curKey: String? = null
    private var currentSong: Song? = null
    private var lyricsJob: Job? = null

    val isRunning: Boolean get() = view != null

    fun canOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun start(context: Context) {
        if (view != null) return
        if (!canOverlay(context)) return
        val app = context.applicationContext
        val wm = app.getSystemService(WindowManager::class.java) ?: return
        val dm = app.resources.displayMetrics
        val h = (dm.heightPixels * 0.42f).toInt()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP }

        val v = LimbusOverlayView(app)
        runCatching { wm.addView(v, params) }.getOrElse {
            android.util.Log.w("LisnOverlay", "添加悬浮窗失败: ${it.message}")
            return
        }
        view = v
        curKey = null
        onSongChanged(currentSong)
    }

    fun stop() {
        val v = view ?: return
        view = null
        lyricsJob?.cancel()
        lyricsJob = null
        curKey = null
        main.post {
            runCatching {
                v.context.getSystemService(WindowManager::class.java)?.removeView(v)
            }
        }
    }

    /** 服务侧:换曲时调用(线程任意);拉歌词并推给视图 */
    fun onSongChanged(song: Song?) {
        onMain {
            currentSong = song
            val v = view ?: return@onMain
            if (song != null && song.key == curKey) return@onMain
            lyricsJob?.cancel()
            lyricsJob = null
            curKey = song?.key
            v.setLyrics(emptyList(), "")
            if (song == null) return@onMain
            lyricsJob = scope.launch {
                if (!fetchLyrics(song, v)) {
                    delay(30_000)
                    if (view === v && song.key == curKey) fetchLyrics(song, v)
                }
            }
        }
    }

    /** 服务侧:进度推送(300ms 级,视图内部对播放插值平滑) */
    fun onPos(posMs: Long, playing: Boolean) {
        onMain { view?.setPos(posMs, playing) }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
    }

    private suspend fun fetchLyrics(song: Song, expectedView: LimbusOverlayView): Boolean {
        return try {
            val lines = withContext(Dispatchers.IO) {
                EngineHub.registry.getLyric(song)?.let { raw ->
                    LrcParser.parse(raw).map { LimbusOverlayView.Line(it.timeMs, it.text) }
                }.orEmpty()
            }
            // A slower request for the previous song must not replace the current lyrics.
            if (view !== expectedView || song.key != curKey) return false
            expectedView.setLyrics(lines, song.artist)
            lines.isNotEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("LisnOverlay", "歌词获取失败: ${e.message}")
            false
        }
    }
}
