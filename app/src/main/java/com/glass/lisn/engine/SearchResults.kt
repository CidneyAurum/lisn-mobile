package com.glass.lisn.engine

import com.glass.lisn.model.SearchPage
import com.glass.lisn.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Stateless page fetching: recommendation searches cannot reset another search's pages. */
internal suspend fun searchProviders(
    providers: List<SourceProvider>,
    keyword: String,
    page: Int,
    timeoutMs: Long = 15_000,
    onFailure: (SourceProvider, Exception) -> Unit = { _, _ -> }
): SearchPage = withContext(Dispatchers.IO) {
    val limit = Semaphore(4)
    val pages = coroutineScope {
        providers.filter { it.caps.supportsSearch && it.health.status != "disabled" }.map { p ->
            async {
                limit.withPermit {
                    try {
                        withTimeout(timeoutMs) { p.search(keyword, page) }
                    } catch (e: Exception) {
                        // A provider timeout may fall back, but a cancelled search must stop.
                        currentCoroutineContext().ensureActive()
                        onFailure(p, e)
                        emptyList()
                    }
                }
            }
        }.awaitAll()
    }
    currentCoroutineContext().ensureActive()
    SearchPage(SearchResults.merge(emptyList(), pages.flatten()), pages.sumOf { it.size } >= 20, page)
}

object SearchResults {
    private val punctuation = setOf('(', ')', '（', '）', '[', ']', '【', '】', '{', '}', '·', '・', '-', '—', '–', '_', ',', '.', '、', '!', '！', '?', '？', ':', '：', ';', '；', '/', '|', '&', '+', '*', '#', '~', '^', '@', '%', '“', '”', '‘', '’', '\\', '\'', '"', '$')
    private val feat = Regex("(?i)feat\\.?")

    private fun normText(s: String): String = buildString {
        for (ch in s) {
            val c = if (ch.code in 0xFF01..0xFF5E) (ch.code - 0xFEE0).toChar() else ch
            if (!c.isWhitespace() && c !in punctuation) append(c.lowercaseChar())
        }
    }

    fun key(name: String, artist: String): String {
        val first = artist.split('/', '、', ',', '&').firstOrNull() ?: artist
        val primary = feat.split(first).firstOrNull() ?: first
        return normText(name) + "|" + normText(primary).take(8)
    }

    /** Merge pages while retaining already loaded covers and each provider's song metadata. */
    fun merge(previous: List<Song>, incoming: List<Song>): List<Song> {
        val merged = linkedMapOf<String, Song>()
        for (song in previous.asSequence() + incoming.asSequence()) {
            if (song.name.isEmpty()) continue
            val key = key(song.name, song.artist)
            val found = merged[key]
            merged[key] = if (found == null) song.copy(key = key) else found.copy(
                origins = (found.origins + song.origins).distinctBy { Triple(it.providerId, it.platform, it.songId) },
                album = found.album ?: song.album,
                picUrl = found.picUrl?.takeIf { it.isNotBlank() } ?: song.picUrl
            )
        }
        return merged.values.toList()
    }

    /** Publish a batch once; no new list/state notification if none of its covers changed. */
    fun withCovers(songs: List<Song>, covers: Map<String, String>): List<Song> {
        if (covers.isEmpty()) return songs
        var changed = false
        val updated = songs.map { song ->
            val pic = covers[song.key]
            if (!pic.isNullOrBlank() && song.picUrl != pic) {
                changed = true
                song.copy(picUrl = pic)
            } else song
        }
        return if (changed) updated else songs
    }
}
