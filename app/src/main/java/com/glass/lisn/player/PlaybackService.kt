package com.glass.lisn.player

import android.content.Intent
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.glass.lisn.R
import com.glass.lisn.EngineHub
import com.glass.lisn.engine.UA
import com.glass.lisn.engine.json
import com.glass.lisn.model.Song
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlin.random.Random

/**
 * 后台播放服务:服务侧持有播放队列,按需解析流地址(播放时才解析,
 * 与桌面端一致),通知栏显示上/下一首自定义按钮,STATE_ENDED 自动切下一曲。
 * 播放模式:loop 列表循环 / one 单曲循环 / shuffle 随机。
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var queue: List<Song> = emptyList()
    private var queueIdx: Int = -1
    private var quality: String = "320k"
    private var pinnedProviderId: String? = null
    private var playMode: String = "loop"
    private var resolveJob: Job? = null
    private var consecutiveFails = 0

    private val player: ExoPlayer?
        get() = mediaSession?.player as? ExoPlayer

    override fun onCreate() {
        super.onCreate()
        quality = EngineHub.settings.get().quality
        playMode = EngineHub.settings.get().playMode

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12000)
            .setReadTimeoutMs(20000)

        val p = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(), true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .build()
        p.addListener(playerListener)
        applyRepeatMode(p)

        val prevButton = CommandButton.Builder()
            .setSessionCommand(SessionCommand(SessionCommands.PREV, Bundle.EMPTY))
            .setDisplayName("上一曲")
            .setIconResId(R.drawable.ic_skip_prev)
            .build()
        val nextButton = CommandButton.Builder()
            .setSessionCommand(SessionCommand(SessionCommands.NEXT, Bundle.EMPTY))
            .setDisplayName("下一曲")
            .setIconResId(R.drawable.ic_skip_next)
            .build()

        mediaSession = MediaSession.Builder(this, p)
            .setCallback(sessionCallback)
            .setCustomLayout(ImmutableList.of(prevButton, nextButton))
            .build()
    }

    private fun applyRepeatMode(p: ExoPlayer) {
        // 单曲循环交给 ExoPlayer 无缝处理(STATE_ENDED 不再触发)
        p.repeatMode = if (playMode == "one") Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) scope.launch { advance(1) }
        }
    }

    private val sessionCallback = object : MediaSession.Callback {
        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            command: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> = when (command.customAction) {
            SessionCommands.PLAY -> {
                val song = args.getString("song")?.let {
                    runCatching { json.decodeFromString(Song.serializer(), it) }.getOrNull()
                }
                val q = args.getString("queue")?.let {
                    runCatching { json.decodeFromString(ListSerializer(Song.serializer()), it) }.getOrNull()
                }
                val idx = args.getInt("index", -1)
                if (song != null) {
                    queue = q ?: listOf(song)
                    queueIdx = if (idx >= 0) idx else queue.indexOfFirst { it.key == song.key }.coerceAtLeast(0)
                    resolveAndPlay()
                }
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            SessionCommands.PLAY_LOCAL -> {
                val uri = args.getString("uri")
                if (uri != null) {
                    playLocal(uri, args.getString("title") ?: "本地音乐", args.getString("artist") ?: "")
                }
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            SessionCommands.NEXT -> { scope.launch { advance(1) }; Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS)) }
            SessionCommands.PREV -> { scope.launch { advance(-1) }; Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS)) }
            SessionCommands.SET_PINNED -> {
                pinnedProviderId = args.getString("pinned")?.ifEmpty { null }
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            SessionCommands.SET_PLAY_MODE -> {
                val mode = args.getString("mode")
                if (mode == "loop" || mode == "one" || mode == "shuffle") {
                    playMode = mode
                    EngineHub.settings.patch(EngineHub.settings.get().copy(playMode = mode))
                    scope.launch(Dispatchers.Main) { player?.let { applyRepeatMode(it) } }
                    broadcast()
                }
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            else -> Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val result = super.onConnect(session, controller)
            // UI 重连时主动推送一次完整状态,否则重连后的界面拿不到服务侧队列
            scope.launch { broadcast() }
            val sessionCommands = result.availableSessionCommands.buildUpon()
                .add(SessionCommand(SessionCommands.PLAY, Bundle.EMPTY))
                .add(SessionCommand(SessionCommands.PLAY_LOCAL, Bundle.EMPTY))
                .add(SessionCommand(SessionCommands.NEXT, Bundle.EMPTY))
                .add(SessionCommand(SessionCommands.PREV, Bundle.EMPTY))
                .add(SessionCommand(SessionCommands.SET_PINNED, Bundle.EMPTY))
                .add(SessionCommand(SessionCommands.SET_PLAY_MODE, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }
    }

    private fun playLocal(uri: String, title: String, artist: String) {
        val p = player ?: return
        scope.launch {
            withContext(Dispatchers.Main) {
                p.setMediaItem(
                    MediaItem.Builder()
                        .setMediaId("local:$uri")
                        .setUri(uri.toUri())
                        .setMediaMetadata(
                            MediaMetadata.Builder().setTitle(title).setArtist(artist).build()
                        )
                        .build()
                )
                p.prepare()
                p.play()
            }
        }
    }

    private fun resolveAndPlay() {
        val p = player ?: return
        val song = queue.getOrNull(queueIdx) ?: return
        resolveJob?.cancel()
        broadcast(loading = true)
        resolveJob = scope.launch {
            try {
                val res = EngineHub.registry.resolveUrl(song, quality, pinnedProviderId)
                val item = MediaItem.Builder()
                    .setMediaId(song.key)
                    .setUri(res.url)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(song.name)
                            .setArtist(song.artist)
                            .setAlbumTitle(song.album)
                            .setArtworkUri(song.picUrl?.toUri())
                            .build()
                    )
                    .build()
                withContext(Dispatchers.Main) {
                    p.setMediaItem(item)
                    p.prepare()
                    p.play()
                }
                consecutiveFails = 0
                broadcast(loading = false, resolve = "${res.providerId}/${res.platform}/${res.quality}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                consecutiveFails++
                broadcast(loading = false, error = if (consecutiveFails >= queue.size) "解析失败:队列内全部音源均不可用(后端可能暂时故障)" else "第 $queueIdx 首解析失败,自动切下一首…")
                if (consecutiveFails < queue.size && queue.size > 1) {
                    // 自动顺序跳下一首(随机模式下也顺序跳,保证不重复不遗漏)
                    queueIdx = (queueIdx + 1) % queue.size
                    val next = queue.getOrNull(queueIdx)
                    if (next != null) { delay(800); resolveAndPlay(); return@launch }
                }
                broadcast(loading = false, error = "解析失败:${e.message ?: e.toString()}")
                withContext(Dispatchers.Main) { p.pause() }
            }
        }
    }

    private suspend fun advance(delta: Int) {
        if (queue.isEmpty()) return
        queueIdx = if (playMode == "shuffle" && queue.size > 1) {
            var next = queueIdx
            while (next == queueIdx) next = Random.nextInt(queue.size)
            next
        } else {
            (queueIdx + delta + queue.size) % queue.size
        }
        resolveAndPlay()
    }

    private fun broadcast(
        loading: Boolean? = null, error: String? = null, resolve: String? = null
    ) {
        val session = mediaSession ?: return
        val args = Bundle().apply {
            putString("queue", json.encodeToString(ListSerializer(Song.serializer()), queue))
            putInt("index", queueIdx)
            putBoolean("loading", loading ?: (resolveJob?.isActive == true))
            putString("mode", playMode)
            if (error != null) putString("error", error)
            if (resolve != null) putString("resolve", resolve)
        }
        session.broadcastCustomCommand(SessionCommand(SessionCommands.STATE, Bundle.EMPTY), args)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val p = player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        scope.cancel()
        super.onDestroy()
    }
}
