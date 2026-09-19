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
import com.glass.lisn.ui.player.LrcLine
import com.glass.lisn.ui.player.LrcParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 桌面歌词悬浮窗管理:SYSTEM_ALERT_WINDOW + 全穿透。
 *  由 PlaybackService 直驱:onSongChanged / onPos,无二次 MediaController 链路。 */
object DesktopLyricsOverlay {

    private var view: LimbusOverlayView? = null
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var curKey: String? = null

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
    }

    fun stop() {
        val v = view ?: return
        view = null
        main.post {
            runCatching {
                v.context.getSystemService(WindowManager::class.java)?.removeView(v)
            }
        }
    }

    /** 服务侧:换曲时调用(线程任意);拉歌词并推给视图 */
    fun onSongChanged(song: Song?) {
        android.util.Log.i("LisnOverlay", "onSongChanged ${song?.key} running=${view != null}")
        val v = view ?: return
        if (song == null || song.key == curKey) return
        curKey = song.key
        hasNoLyrics = true
        main.post { v.setLyrics(emptyList(), "") }
        scope.launch { fetchLyrics(song) }
        scope.launch {
            kotlinx.coroutines.delay(30_000)
            if (view != null && song.key == curKey && hasNoLyrics) fetchLyrics(song)
        }
    }

    @Volatile
    private var hasNoLyrics = true

    /** 服务侧:进度推送(300ms 级,视图内部对播放插值平滑) */
    fun onPos(posMs: Long, playing: Boolean) {
        main.post { view?.setPos(posMs, playing) }
    }

    private suspend fun fetchLyrics(song: Song) {
        android.util.Log.i("LisnOverlay", "fetchLyrics begin ${song.key}")
        runCatching {
            val raw = withContext(Dispatchers.IO) { EngineHub.registry.getLyric(song) }
            android.util.Log.i("LisnOverlay", "fetchLyrics len=${raw?.length ?: -1}")
            if (raw == null) { hasNoLyrics = true; return }
            hasNoLyrics = false
            val parsed: List<LrcLine> = LrcParser.parse(raw)
            android.util.Log.i("LisnOverlay", "parsed=" + parsed.size + " first3=" + parsed.take(3).joinToString(" | ") { it.timeMs.toString() + ":" + it.text })
            val list = parsed.map { LimbusOverlayView.Line(it.timeMs, it.text) }
            main.post { view?.setLyrics(list, song.artist) }
        }.onFailure { android.util.Log.w("LisnOverlay", "歌词获取失败: ${it.message}") }
    }
}
