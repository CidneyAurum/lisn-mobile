package com.glass.lisn.player

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.glass.lisn.engine.json
import com.glass.lisn.model.Song
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    const val SET_PLAY_MODE = "glass.setPlayMode"
    const val CLEAR_QUEUE = "glass.clearQueue"
    const val SLEEP_TIMER = "glass.sleepTimer"
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
    private val _playMode = MutableStateFlow("loop")
    val playMode: StateFlow<String> = _playMode
    private val _playback = MutableStateFlow(PlaybackState())
    val playback: StateFlow<PlaybackState> = _playback

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var ticker: Job? = null
    private var lastQueueJson: String? = null
    private var closed = false

    fun connect() {
        if (closed) return
        if (controller != null) return
        if (controllerFuture != null) return
        val future = MediaController.Builder(
            appContext, androidx.media3.session.SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        ).setListener(controllerListener).buildAsync()
        controllerFuture = future
        future.addListener({
            if (controllerFuture !== future) {
                if (!future.isCancelled) runCatching { future.get().release() }
                return@addListener
            }
            try {
                controller = future.get().also { c ->
                    c.addListener(playerListener)
                }
                controllerFuture = null
                syncPlayerState()
                startTicker()
            } catch (e: Throwable) {
                if (!future.isCancelled) android.util.Log.e("PlayerClient", "连接播放服务失败", e)
                controllerFuture = null
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    /** 接收服务侧状态广播(队列快照/加载/错误) */
    private val controllerListener = object : androidx.media3.session.MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            if (this@PlayerClient.controller !== controller) return
            ticker?.cancel()
            ticker = null
            this@PlayerClient.controller = null
            _isPlaying.value = false
            _isBuffering.value = false
        }

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
            _currentLocalTitle.value = mediaItem?.takeIf { it.mediaId.startsWith("local:") }
                ?.mediaMetadata?.title?.toString()
            _durationMs.value = 0
            _positionMs.value = 0
        }
        override fun onEvents(player: Player, events: Player.Events) { syncPlayerState() }
    }

    private fun syncPlayerState() {
        val c = controller ?: return
        _isPlaying.value = c.isPlaying
        _isBuffering.value = c.playbackState == Player.STATE_BUFFERING
        _positionMs.value = if (c.playbackState == Player.STATE_IDLE) 0 else c.currentPosition.coerceAtLeast(0)
        _durationMs.value = if (c.playbackState == Player.STATE_READY || c.playbackState == Player.STATE_BUFFERING) {
            c.duration.coerceAtLeast(0)
        } else 0
        _currentMediaId.value = c.currentMediaItem?.mediaId
        _currentLocalTitle.value = c.currentMediaItem?.takeIf { it.mediaId.startsWith("local:") }
            ?.mediaMetadata?.title?.toString()
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
        val encodedQueue = lastQueueJson?.takeIf { queue == _playback.value.queue }
            ?: json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Song.serializer()), queue)
        val args = Bundle().apply {
            putString("song", json.encodeToString(Song.serializer(), song))
            putString("queue", encodedQueue)
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

    fun toggle() {
        val c = controller?.takeIf { it.isCommandAvailable(Player.COMMAND_PLAY_PAUSE) } ?: return
        val state = _playback.value
        val song = state.queue.getOrNull(state.queueIdx)
        if (c.playbackState == Player.STATE_IDLE && !state.loading) {
            if (song != null) {
                play(song, state.queue, state.queueIdx)
            } else if (c.mediaItemCount > 0) {
                c.prepare()
                c.play()
            }
            return
        }
        // playWhenReady also represents the user's intent while buffering or resolving.
        if (c.playWhenReady) c.pause() else c.play()
    }
    fun seekTo(ms: Long) {
        controller?.let { c ->
            val target = ms.coerceAtLeast(0)
            c.seekTo(target)
            _positionMs.value = target
        }
    }
    fun next() { controller?.sendCustomCommand(SessionCommand(SessionCommands.NEXT, Bundle.EMPTY), Bundle.EMPTY) }
    fun prev() { controller?.sendCustomCommand(SessionCommand(SessionCommands.PREV, Bundle.EMPTY), Bundle.EMPTY) }

    fun setPinned(pinned: String?) {
        val args = Bundle().apply { putString("pinned", pinned) }
        controller?.sendCustomCommand(SessionCommand(SessionCommands.SET_PINNED, Bundle.EMPTY), args)
    }

    /** minutes<=0 取消定时 */
    fun clearQueue() {
        controller?.sendCustomCommand(SessionCommand(SessionCommands.CLEAR_QUEUE, Bundle.EMPTY), Bundle.EMPTY)
    }

    fun setSleepTimer(minutes: Int) {
        val args = Bundle().apply { putInt("minutes", minutes) }
        controller?.sendCustomCommand(SessionCommand(SessionCommands.SLEEP_TIMER, Bundle.EMPTY), args)
    }

    fun setPlayMode(mode: String) {
        val args = Bundle().apply { putString("mode", mode) }
        controller?.sendCustomCommand(SessionCommand(SessionCommands.SET_PLAY_MODE, Bundle.EMPTY), args)
    }

    /** 控制器侧接收服务广播(在 service 中回调,见 PlaybackService.stateBroadcast) */
    fun onStateBroadcast(args: Bundle) {
        val queueJson = args.getString("queue")
        val queue = if (queueJson == null || queueJson == lastQueueJson) _playback.value.queue else {
            runCatching {
                json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Song.serializer()), queueJson)
            }.getOrNull()?.also { lastQueueJson = queueJson } ?: _playback.value.queue
        }
        _playback.value = PlaybackState(
            queue = queue,
            queueIdx = args.getInt("index", -1),
            loading = args.getBoolean("loading", false),
            error = args.getString("error"),
            resolveInfo = args.getString("resolve")
        )
        _currentLocalTitle.value = args.getString("localTitle")
        args.getString("mode")?.let { if (it.isNotEmpty()) _playMode.value = it }
    }

    suspend fun release() {
        withContext(Dispatchers.Main) {
            ticker?.cancel()
            ticker = null
            val pending = controllerFuture
            controllerFuture = null
            pending?.let { MediaController.releaseFuture(it) }
            controller?.release()
            controller = null
            _isPlaying.value = false
            _isBuffering.value = false
        }
    }

    /** viewModelScope is already cancelled when onCleared runs; finish on our own main scope. */
    fun close() {
        if (closed) return
        closed = true
        scope.launch(Dispatchers.Main.immediate) {
            try { release() } finally { scope.cancel() }
        }
    }
}
