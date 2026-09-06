package com.glass.lisn.player

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.glass.lisn.EngineHub
import com.glass.lisn.engine.json
import com.glass.lisn.model.Song
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 服务 ⇄ UI 的会话命令 */
object SessionCommands {
    const val PLAY = "glass.play"
    const val PLAY_LOCAL = "glass.playLocal"
    const val PREV = "glass.prev"
    const val NEXT = "glass.next"
    const val STATE = "glass.state"
    const val SET_PINNED = "glass.setPinned"
}

/** 一次播放状态的广播(歌单快照 + 当前曲 + 加载/错误) */
data class PlaybackState(
    val queue: List<Song> = emptyList(),
    val queueIdx: Int = -1,
    val loading: Boolean = false,
    val error: String? = null,
    val resolveInfo: String? = null
)

/**
 * UI 侧 MediaController 封装:发送播放命令,接收状态广播与播放器事件。
 */
class PlayerClient(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying
    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering
    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs
    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs
    private val _currentMediaId = MutableStateFlow<String?>(null)
    val currentMediaId: StateFlow<String?> = _currentMediaId
    private val _currentLocalTitle = MutableStateFlow<String?>(null)
    val currentLocalTitle: StateFlow<String?> = _currentLocalTitle
    private val _playback = MutableStateFlow(PlaybackState())
    val playback: StateFlow<PlaybackState> = _playback

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var ticker: Job? = null

    fun connect() {
        if (controller != null) return
        if (controllerFuture != null) return
        val future = MediaController.Builder(
            appContext, androidx.media3.session.SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        ).setListener(controllerListener).buildAsync()
        controllerFuture = future
        future.addListener({
            try {
                controller = future.get().also { c ->
                    c.addListener(playerListener)
                }
                startTicker()
            } catch (e: Throwable) {
                android.util.Log.e("PlayerClient", "连接播放服务失败", e)
                controllerFuture = null
            }
        }, { it.run() })
    }

    /** 接收服务侧状态广播(队列快照/加载/错误) */
    private val controllerListener = object : androidx.media3.session.MediaController.Listener {
        override fun onCustomCommand(
            controller: MediaController,
            command: androidx.media3.session.SessionCommand,
            args: android.os.Bundle
        ): com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.SessionResult> {
            if (command.customAction == SessionCommands.STATE) onStateBroadcast(args)
            return com.google.common.util.concurrent.Futures.immediateFuture(
                androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS)
            )
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) { _isPlaying.value = isPlaying }
        override fun onPlaybackStateChanged(playbackState: Int) {
            _isBuffering.value = playbackState == Player.STATE_BUFFERING
            val c = controller ?: return
            _durationMs.value = if (playbackState == Player.STATE_READY) c.duration.coerceAtLeast(0) else 0
            if (playbackState == Player.STATE_IDLE) _positionMs.value = 0
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            _currentMediaId.value = mediaItem?.mediaId
            _durationMs.value = 0
            _positionMs.value = 0
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val c = controller
                if (c != null && c.isPlaying) {
                    _positionMs.value = c.currentPosition.coerceAtLeast(0)
                    _durationMs.value = c.duration.coerceAtLeast(0)
                }
                delay(500)
            }
        }
    }

    // ---------- 命令 ----------

    fun play(song: Song, queue: List<Song>, index: Int = -1) {
        val c = controller ?: return
        val args = Bundle().apply {
            putString("song", json.encodeToString(Song.serializer(), song))
            putString("queue", json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Song.serializer()), queue))
            putInt("index", index)
        }
        c.sendCustomCommand(SessionCommand(SessionCommands.PLAY, Bundle.EMPTY), args)
    }

    fun playLocal(uri: String, title: String, artist: String) {
        val c = controller ?: return
        val args = Bundle().apply {
            putString("uri", uri); putString("title", title); putString("artist", artist)
        }
        c.sendCustomCommand(SessionCommand(SessionCommands.PLAY_LOCAL, Bundle.EMPTY), args)
    }

    fun toggle() { controller?.takeIf { it.isCommandAvailable(Player.COMMAND_PLAY_PAUSE) }?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun seekTo(ms: Long) { controller?.seekTo(ms) }
    fun next() { controller?.sendCustomCommand(SessionCommand(SessionCommands.NEXT, Bundle.EMPTY), Bundle.EMPTY) }
    fun prev() { controller?.sendCustomCommand(SessionCommand(SessionCommands.PREV, Bundle.EMPTY), Bundle.EMPTY) }

    fun setPinned(pinned: String?) {
        val args = Bundle().apply { putString("pinned", pinned) }
        controller?.sendCustomCommand(SessionCommand(SessionCommands.SET_PINNED, Bundle.EMPTY), args)
    }

    /** 控制器侧接收服务广播(在 service 中回调,见 PlaybackService.stateBroadcast) */
    fun onStateBroadcast(args: Bundle) {
        val queueJson = args.getString("queue") ?: return
        val queue = runCatching {
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Song.serializer()), queueJson)
        }.getOrDefault(emptyList())
        _playback.value = PlaybackState(
            queue = queue,
            queueIdx = args.getInt("index", -1),
            loading = args.getBoolean("loading", false),
            error = args.getString("error"),
            resolveInfo = args.getString("resolve")
        )
        _currentLocalTitle.value = args.getString("localTitle")
    }

    suspend fun release() {
        withContext(Dispatchers.Main) {
            controller?.release()
            controller = null
            controllerFuture = null
        }
    }
}
