package com.glass.lisn.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glass.lisn.EngineHub
import com.glass.lisn.data.DownloadManager
import com.glass.lisn.engine.HttpSourceConfig
import com.glass.lisn.engine.SearchResults
import com.glass.lisn.engine.dailyKeywords
import com.glass.lisn.model.LibraryFile
import com.glass.lisn.model.Settings
import com.glass.lisn.model.Song
import com.glass.lisn.model.SourcesSnapshot
import com.glass.lisn.model.UserPlaylist
import com.glass.lisn.player.PlayerClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class View(val label: String) {
    DISCOVER("发现"), SEARCH("搜索"), PLAYLISTS("歌单"),
    DOWNLOADS("下载"), LIBRARY("本地"), SOURCES("音源"), SETTINGS("设置")
}

/** 全局应用状态(对应桌面端 zustand store) */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    val player = PlayerClient(app)

    var view by mutableStateOf(View.DISCOVER)
    var toast by mutableStateOf<String?>(null)
    var nowPlayingOpen by mutableStateOf(false)
    private var toastJob: Job? = null

    // 搜索
    var keyword by mutableStateOf("")
    var results by mutableStateOf<List<Song>>(emptyList())
    var searching by mutableStateOf(false)
    var loadingMore by mutableStateOf(false)
    var hasMore by mutableStateOf(false)
    var page by mutableStateOf(1)
    var searched by mutableStateOf(false)
    var searchError by mutableStateOf<String?>(null)
    private val picPending = mutableSetOf<String>()
    private val picRequests = linkedMapOf<String, Song>()
    private var picJob: Job? = null
    private var searchJob: Job? = null
    private var searchVersion = 0L
    private var searchedKeyword = ""

    // 播放联动:UI 通过 player.playback / isPlaying 等 StateFlow collectAsState 观察

    // 音源 / 设置 / 歌单 / 下载 / 本地库
    var sources by mutableStateOf<SourcesSnapshot?>(null)
    var settings by mutableStateOf(EngineHub.settings.get())
    var playlists by mutableStateOf<List<UserPlaylist>>(emptyList())
    var library by mutableStateOf<List<LibraryFile>>(emptyList())
    var searchHistory by mutableStateOf(EngineHub.searchHistory.items)
    /** 下载队列流(UI 用 collectAsState 订阅) */
    val downloads get() = EngineHub.downloads.queue

    init {
        // 下载完成后自动刷新本地音乐列表
        viewModelScope.launch {
            var lastCompleted = -1
            EngineHub.downloads.queue.collect { list ->
                val done = list.count { it.status == com.glass.lisn.model.DownloadStatus.COMPLETED }
                if (done != lastCompleted) {
                    lastCompleted = done
                    library = DownloadManager.scanLibrary(getApplication())
                }
            }
        }
        player.connect()
        // 桌面歌词:上次开启且已有悬浮窗权限则自动恢复
        val s0 = EngineHub.settings.get()
        if (s0.desktopLyrics && com.glass.lisn.ui.overlay.DesktopLyricsOverlay.canOverlay(getApplication())) {
            com.glass.lisn.ui.overlay.DesktopLyricsOverlay.start(getApplication())
        }
        refreshSources()
        refreshPlaylists()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                EngineHub.registry.mf.loadAll()
                EngineHub.registry.lx.loadAll()
                EngineHub.rebuild()
            }
            refreshSources()
        }
        if (settings.autoCheckUpdates) viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { EngineHub.registry.mf.checkUpdates() }
                runCatching { EngineHub.registry.lx.checkUpdates() }
                EngineHub.rebuild()
            }
            refreshSources()
        }
    }

    fun showToast(msg: String) {
        toastJob?.cancel()
        toast = msg
        toastJob = viewModelScope.launch {
            delay(3200)
            if (toast == msg) toast = null
        }
    }

    // ---------- 搜索 ----------

    fun doSearch(kw: String? = null) {
        val k = (kw ?: keyword).trim()
        if (k.isEmpty()) return
        startSearch(k)
    }

    private fun startSearch(k: String, playFirst: Boolean = false) {
        val version = ++searchVersion
        searchJob?.cancel()
        picJob?.cancel()
        picJob = null
        picRequests.clear()
        picPending.clear()
        keyword = k
        searchedKeyword = k
        searching = true
        loadingMore = false
        searched = true
        searchError = null
        results = emptyList()
        page = 1
        hasMore = false
        searchJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { EngineHub.searchHistory.record(k) }
                if (version != searchVersion) return@launch
                searchHistory = EngineHub.searchHistory.items
                val r = EngineHub.registry.search(k, 1)
                ensureActive()
                if (version != searchVersion) return@launch
                results = r.songs
                hasMore = r.hasMore
                ensurePics(r.songs)
                if (playFirst) {
                    view = View.SEARCH
                    if (r.songs.isEmpty()) showToast("「$k」无结果")
                    else play(r.songs.first(), r.songs)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (version == searchVersion) {
                    searchError = e.message ?: e.toString()
                    if (playFirst) showToast("播放失败:$searchError")
                }
            } finally {
                if (version == searchVersion) searching = false
            }
        }
    }

    fun loadMore() {
        if (searchedKeyword.isEmpty() || searching || loadingMore || !hasMore) return
        val version = searchVersion
        val k = searchedKeyword
        val nextPage = page + 1
        loadingMore = true
        searchJob = viewModelScope.launch {
            try {
                val r = EngineHub.registry.search(k, nextPage)
                ensureActive()
                if (version != searchVersion) return@launch
                // Use the current list after the request, preserving covers loaded meanwhile.
                results = SearchResults.merge(results, r.songs)
                hasMore = r.hasMore
                page = r.page
                ensurePics(results)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                if (version == searchVersion) hasMore = false
            } finally {
                if (version == searchVersion) loadingMore = false
            }
        }
    }

    /** Keep at most four cover requests active; publish one result-list update per batch. */
    private fun ensurePics(songs: List<Song>) {
        for (s in songs.take(60)) {
            if (s.picUrl.isNullOrEmpty() && picPending.add(s.key)) picRequests[s.key] = s
        }
        if (picJob?.isActive == true || picRequests.isEmpty()) return
        val version = searchVersion
        picJob = viewModelScope.launch {
            while (picRequests.isNotEmpty()) {
                val batch = picRequests.values.take(4)
                batch.forEach { picRequests.remove(it.key) }
                val covers = coroutineScope {
                    batch.map { song ->
                        async {
                            try {
                                EngineHub.registry.getPic(song)?.let { song.key to it }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }.awaitAll().filterNotNull().toMap()
                }
                ensureActive()
                if (version != searchVersion) return@launch
                results = SearchResults.withCovers(results, covers)
                batch.forEach { picPending.remove(it.key) }
            }
        }
    }

    // ---------- 播放 ----------

    fun play(song: Song, list: List<Song> = results) {
        player.play(song, list.ifEmpty { listOf(song) })
        ensurePics(listOf(song))
    }

    fun playLocal(f: LibraryFile) {
        player.playLocal(f.uri, f.name, f.artist)
    }

    // ---------- 音源 ----------

    fun refreshSources() {
        sources = EngineHub.registry.snapshot()
    }

    fun setMfEnabled(id: String, enabled: Boolean) {
        EngineHub.registry.mf.setEnabled(id, enabled)
        EngineHub.rebuild()
        refreshSources()
        if (enabled) viewModelScope.launch {
            withContext(Dispatchers.IO) {
                EngineHub.registry.mf.loadAll()
                EngineHub.rebuild()
            }
            refreshSources()
        }
    }

    fun setLxEnabled(id: String, enabled: Boolean) {
        EngineHub.registry.lx.setEnabled(id, enabled)
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (enabled) EngineHub.registry.lx.loadAll()
                EngineHub.rebuild()
            }
            refreshSources()
        }
    }

    fun addLxScript(name: String, url: String, onDone: (String) -> Unit) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val (ok, detail) = EngineHub.registry.lx.addCustom(name, url)
            EngineHub.rebuild()
            refreshSources()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onDone(detail) }
        }
    }

    fun upgradeLx(id: String, onDone: (Boolean, String) -> Unit) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val (ok, detail) = EngineHub.registry.lx.upgrade(id)
            EngineHub.rebuild()
            refreshSources()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onDone(ok, detail) }
        }
    }

    fun upgradeMf(id: String, onDone: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val (ok, detail) = withContext(Dispatchers.IO) {
                val result = EngineHub.registry.mf.upgrade(id)
                EngineHub.rebuild()
                result
            }
            refreshSources()
            onDone(ok, detail)
        }
    }

    fun checkUpdates(onDone: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try { EngineHub.registry.mf.checkUpdates() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Keep cached sources if the update server is unavailable. */ }
                EngineHub.rebuild()
            }
            refreshSources()
            onDone()
        }
    }

    fun setMode(mode: String) {
        EngineHub.registry.mode = mode
        settings = EngineHub.settings.patch(settings.copy(mode = mode))
        refreshSources()
    }

    fun setHttpEnabled(id: String, enabled: Boolean) {
        val tpls = EngineHub.loadTemplates().map {
            if (it.id == id) it.copy(enabled = enabled) else it
        }
        EngineHub.saveTemplates(tpls)
        EngineHub.rebuild()
        refreshSources()
    }

    fun addHttpTemplate(name: String, urlTemplate: String, onDone: (String) -> Unit) {
        if (name.isBlank() || !urlTemplate.contains("{source}") || !urlTemplate.contains("{songId}")) {
            onDone("模板需包含 {source} 与 {songId} 占位符")
            return
        }
        val tpls = EngineHub.loadTemplates() + HttpSourceConfig(
            id = "http-" + System.currentTimeMillis().toString(36),
            name = name.trim(),
            urlTemplate = urlTemplate.trim(),
            platforms = listOf("kw", "kg", "tx", "wy", "mg"),
            qualities = listOf("320k", "128k")
        )
        EngineHub.saveTemplates(tpls)
        EngineHub.rebuild()
        refreshSources()
        onDone("已添加 HTTP 音源模板")
    }

    fun testSource(id: String, onDone: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val p = EngineHub.registry.providers.find { it.id == id }
            if (p == null) { onDone(false, "未找到音源"); return@launch }
            try {
                val (ok, detail) = withContext(Dispatchers.IO) { p.test() }
                onDone(ok, detail)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                onDone(false, e.message ?: e.toString())
            }
            refreshSources()
        }
    }

    // ---------- 设置 ----------

    fun patchSettings(p: Settings) {
        settings = EngineHub.settings.patch(p)
        EngineHub.registry.mode = settings.mode
    }

    // ---------- 歌单 ----------

    fun refreshPlaylists() {
        playlists = EngineHub.playlists.list()
    }

    fun createPlaylist(name: String, onDone: (UserPlaylist) -> Unit) {
        val pl = EngineHub.playlists.create(name)
        refreshPlaylists()
        onDone(pl)
    }

    fun createPlaylistGet(name: String): UserPlaylist {
        val pl = EngineHub.playlists.create(name)
        refreshPlaylists()
        return pl
    }

    fun exportPlaylistJson(id: String): String? = EngineHub.playlists.exportJson(id)

    fun importPlaylistJson(text: String) {
        val (ok, detail) = EngineHub.playlists.importJson(text)
        refreshPlaylists()
        showToast(detail)
    }

    fun renamePlaylist(id: String, name: String) {
        EngineHub.playlists.rename(id, name)
        refreshPlaylists()
    }

    fun deletePlaylist(id: String) {
        EngineHub.playlists.delete(id)
        refreshPlaylists()
    }

    fun addSongToPlaylist(id: String, song: Song) {
        val (ok, detail) = EngineHub.playlists.addSong(id, song)
        refreshPlaylists()
        showToast(detail)
    }

    fun removeSongFromPlaylist(id: String, songKey: String) {
        EngineHub.playlists.removeSong(id, songKey)
        refreshPlaylists()
    }

    fun saveSearchToPlaylist(name: String) {
        if (results.isEmpty()) return
        EngineHub.playlists.saveFromSearch(name, keyword, results)
        refreshPlaylists()
        showToast("已保存「$name」(${results.size} 首)")
    }

    // ---------- 下载 ----------

    fun enqueueDownload(song: Song) {
        EngineHub.downloads.enqueue(song, settings.quality)
        showToast("已加入下载队列(${settings.quality})")
    }

    fun cancelDownload(id: String) = EngineHub.downloads.cancel(id)
    fun retryDownload(id: String) = EngineHub.downloads.retry(id)
    fun removeDownload(id: String) = EngineHub.downloads.remove(id)
    fun clearFinishedDownloads() = EngineHub.downloads.clearFinished()

    // ---------- 本地库 ----------

    /** 每日推荐一键播放:搜索关键词并直接播放首曲(入队整页结果) */
    fun playKeyword(kw: String) {
        val k = kw.trim()
        if (k.isNotEmpty()) startSearch(k, playFirst = true)
    }

    fun removeSearchHistory(kw: String) {
        EngineHub.searchHistory.remove(kw)
        searchHistory = EngineHub.searchHistory.items
    }

    fun clearSearchHistory() {
        EngineHub.searchHistory.clear()
        searchHistory = emptyList()
    }

    fun refreshLibrary() {
        viewModelScope.launch {
            library = DownloadManager.scanLibrary(getApplication())
        }
    }

    override fun onCleared() {
        player.close()
        super.onCleared()
    }
}
