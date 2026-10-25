package com.glass.lisn.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.util.AtomicFile
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
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
import java.io.File
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
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
    // MediaSession callbacks, player access and queue mutations share the player looper.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistenceUpdates = Channel<PlaybackQueueSnapshot>(Channel.CONFLATED)
    private val queueStorageLock = Any()
    private var queue: List<Song> = emptyList()
    private var queueJson = "[]"
    private var queueRevision = 0L
    private var queueIdx: Int = -1
    private var pinnedProviderId: String? = null
    private var playMode: String = "loop"
    private var resolveJob: Job? = null
    private var consecutiveFails = 0
    private var sleepJob: Job? = null
    private var playbackRequest = 0L
    private var resolving = false
    private var playbackError: String? = null
    private var resolveInfo: String? = null
    private var localTitle: String? = null

    private val player: ExoPlayer?
        get() = mediaSession?.player as? ExoPlayer

    override fun onCreate() {
        super.onCreate()
        playMode = EngineHub.settings.get().playMode
        persistenceScope.launch {
            try {
                for (snapshot in persistenceUpdates) {
                    runCatching {
                        val data = snapshot.encode().toByteArray(Charsets.UTF_8)
                        synchronized(queueStorageLock) {
                            val file = AtomicFile(queueFile())
                            val output = file.startWrite()
                            try {
                                output.write(data)
                                file.finishWrite(output)
                            } catch (e: Throwable) {
                                file.failWrite(output)
                                throw e
                            }
                        }
                    }.onFailure { android.util.Log.w("LisnPlayback", "保存队列失败", it) }
                }
            } finally {
                persistenceScope.cancel()
            }
        }
        restoreQueue()

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

        // 桌面歌词:进度循环(300ms 级推送,视图内部插值平滑)
        scope.launch {
            var previousPosition = -1L
            var previousPlaying = false
            while (coroutineContext.isActive) {
                val pl = player
                if (pl != null && com.glass.lisn.ui.overlay.DesktopLyricsOverlay.isRunning) {
                    val position = pl.currentPosition.coerceAtLeast(0)
                    val playing = pl.isPlaying
                    if (position != previousPosition || playing != previousPlaying) {
                        com.glass.lisn.ui.overlay.DesktopLyricsOverlay.onPos(position, playing)
                        previousPosition = position
                        previousPlaying = playing
                    }
                } else {
                    previousPosition = -1L
                }
                delay(300)
            }
        }

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

        val sessionActivity = PendingIntent.getActivity(
            this, 0,
            Intent(this, Class.forName("com.glass.lisn.MainActivity")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        mediaSession = MediaSession.Builder(this, p)
            .setSessionActivity(sessionActivity)
            .setCallback(sessionCallback)
            .setCustomLayout(ImmutableList.of(prevButton, nextButton))
            .build()
    }

    private fun queueFile(): File = File(filesDir, "last-queue.json")

    /** 应用重启后恢复上次队列(不自动播放,等用户点播放) */
    private fun restoreQueue() {
        val revision = queueRevision
        scope.launch {
            val restored = withContext(Dispatchers.IO) {
                runCatching {
                    synchronized(queueStorageLock) {
                        AtomicFile(queueFile()).openRead().bufferedReader().use {
                            PlaybackQueueSnapshot.decode(it.readText())
                        }
                    }
                }.getOrNull()
            } ?: return@launch
            // A play/clear command arriving during disk IO takes precedence over restoration.
            if (queueRevision != revision) return@launch
            replaceQueue(restored.queue)
            queueIdx = restored.index
            android.util.Log.i("LisnPlayback", "恢复队列: ${queue.size} 首, idx=$queueIdx")
            broadcast()
        }
    }

    private fun replaceQueue(songs: List<Song>) {
        queueRevision++
        if (songs == queue) return
        queue = songs
        queueJson = json.encodeToString(ListSerializer(Song.serializer()), songs)
    }

    private fun persistQueue() {
        // One writer preserves ordering; rapid skips only persist the latest pending snapshot.
        persistenceUpdates.trySend(PlaybackQueueSnapshot(queue, queueIdx))
    }

    private fun cancelResolve() {
        playbackRequest++
        resolveJob?.cancel()
        resolveJob = null
        resolving = false
    }

    private fun applyRepeatMode(p: ExoPlayer) {
        // 单曲循环交给 ExoPlayer 无缝处理(STATE_ENDED 不再触发)
        p.repeatMode = if (playMode == "one") Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) scope.launch { advance(1) }
        }

        override fun onPlayerError(error: PlaybackException) {
            resolving = false
            playbackError = "播放失败:${error.message ?: error.errorCodeName}"
            broadcast()
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
                    val selection = PlaybackQueueSnapshot.select(song, q, idx)
                    replaceQueue(selection.queue)
                    queueIdx = selection.index
                    consecutiveFails = 0
                    localTitle = null
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
            SessionCommands.CLEAR_QUEUE -> {
                cancelResolve()
                replaceQueue(emptyList()); queueIdx = -1; consecutiveFails = 0
                localTitle = null; playbackError = null; resolveInfo = null
                persistQueue()
                player?.pause()
                player?.clearMediaItems()
                com.glass.lisn.ui.overlay.DesktopLyricsOverlay.onSongChanged(null)
                broadcast()
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            SessionCommands.SLEEP_TIMER -> {
                sleepJob?.cancel()
                val minutes = args.getInt("minutes", 0)
                if (minutes > 0) {
                    sleepJob = scope.launch {
                        delay(minutes * 60_000L)
                        player?.pause()
                        playbackError = "睡眠时间到,已暂停播放"
                        broadcast()
                    }
                }
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            SessionCommands.SET_PLAY_MODE -> {
                val mode = args.getString("mode")
                if (mode == "loop" || mode == "one" || mode == "shuffle") {
                    playMode = mode
                    EngineHub.settings.patch(EngineHub.settings.get().copy(playMode = mode))
                    player?.let { applyRepeatMode(it) }
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
            val sessionCommands = result.availableSessionCommands.buildUpon()
                .add(SessionCommand(SessionCommands.PLAY, Bundle.EMPTY))
                .add(SessionCommand(SessionCommands.PLAY_LOCAL, Bundle.EMPTY))
                .add(SessionCommand(SessionCommands.NEXT, Bundle.EMPTY))
                .add(SessionCommand(SessionCommands.PREV, Bundle.EMPTY))
                .add(SessionCommand(SessionCommands.SET_PINNED, Bundle.EMPTY))
                .add(SessionCommand(SessionCommands.SET_PLAY_MODE, Bundle.EMPTY))
                .add(SessionCommand(SessionCommands.SLEEP_TIMER, Bundle.EMPTY))
                .add(SessionCommand(SessionCommands.CLEAR_QUEUE, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
            // onConnect runs before the controller is connected, so its broadcasts can be lost.
            session.sendCustomCommand(controller, SessionCommand(SessionCommands.STATE, Bundle.EMPTY), stateArgs())
        }
    }

    private fun playLocal(uri: String, title: String, artist: String) {
        val p = player ?: return
        cancelResolve()
        // Keep the online queue available for Next, but display the local item as current.
        queueRevision++
        queueIdx = -1
        consecutiveFails = 0
        localTitle = title
        playbackError = null
        resolveInfo = null
        com.glass.lisn.ui.overlay.DesktopLyricsOverlay.onSongChanged(
            Song(key = "local:$uri", name = title, artist = artist)
        )
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
        broadcast()
    }

    private fun resolveAndPlay(autoPlay: Boolean = true) {
        val p = player ?: return
        val song = queue.getOrNull(queueIdx) ?: return
        cancelResolve()
        val request = playbackRequest
        val q = EngineHub.settings.get().quality  // 实时读取:切音质后无需重启服务
        val pinned = pinnedProviderId
        resolving = true
        localTitle = null
        playbackError = null
        resolveInfo = null
        // Stop the previous stream while resolving. A Pause received during IO remains effective.
        p.stop()
        p.playWhenReady = autoPlay
        com.glass.lisn.ui.overlay.DesktopLyricsOverlay.onSongChanged(null)
        broadcast()
        resolveJob = scope.launch {
            try {
                val res = withContext(Dispatchers.IO) { EngineHub.registry.resolveUrl(song, q, pinned) }
                if (request != playbackRequest) return@launch
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
                p.setMediaItem(item)
                p.prepare()
                consecutiveFails = 0
                resolving = false
                resolveInfo = "${res.providerId}/${res.platform}/${res.quality}"
                com.glass.lisn.ui.overlay.DesktopLyricsOverlay.onSongChanged(song)
                broadcast()
                persistQueue()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (request != playbackRequest) return@launch
                consecutiveFails++
                resolving = false
                playbackError = if (consecutiveFails >= queue.size) "解析失败:队列内全部音源均不可用(后端可能暂时故障)" else "第 ${queueIdx + 1} 首解析失败,自动切下一首…"
                broadcast()
                if (consecutiveFails < queue.size && queue.size > 1) {
                    // 自动顺序跳下一首(随机模式下也顺序跳,保证不重复不遗漏)
                    delay(800)
                    if (request != playbackRequest) return@launch
                    queueIdx = (queueIdx + 1) % queue.size
                    resolveAndPlay(autoPlay = p.playWhenReady)
                    return@launch
                }
                playbackError = "解析失败:${e.message ?: e.toString()}"
                broadcast()
                p.pause()
            }
        }
    }

    private suspend fun advance(delta: Int) {
        if (queue.isEmpty()) return
        consecutiveFails = 0
        queueIdx = if (queueIdx < 0) {
            if (delta >= 0) 0 else queue.lastIndex
        } else if (playMode == "shuffle" && queue.size > 1) {
            var next = queueIdx
            while (next == queueIdx) next = Random.nextInt(queue.size)
            next
        } else {
            (queueIdx + delta + queue.size) % queue.size
        }
        resolveAndPlay()
    }

    private fun stateArgs(): Bundle = Bundle().apply {
        putString("queue", queueJson)
        putInt("index", queueIdx)
        putBoolean("loading", resolving)
        putString("mode", playMode)
        putString("localTitle", localTitle)
        putString("error", playbackError)
        putString("resolve", resolveInfo)
    }

    private fun broadcast() {
        mediaSession?.broadcastCustomCommand(SessionCommand(SessionCommands.STATE, Bundle.EMPTY), stateArgs())
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val p = player
        if (!resolving && (p == null || !p.playWhenReady || p.mediaItemCount == 0)) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        cancelResolve()
        com.glass.lisn.ui.overlay.DesktopLyricsOverlay.onSongChanged(null)
        persistenceUpdates.close()
        scope.cancel()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
