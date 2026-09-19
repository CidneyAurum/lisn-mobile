package com.glass.lisn.data

import android.content.Context
import com.glass.lisn.engine.json
import com.glass.lisn.model.Song
import com.glass.lisn.model.UserPlaylist
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable
private data class PlaylistFile(val playlists: List<UserPlaylist>)

class PlaylistStore(context: Context) {
    private val file = java.io.File(context.filesDir, "playlists.json")

    private fun load(): MutableList<UserPlaylist> = runCatching {
        json.decodeFromString(PlaylistFile.serializer(), file.readText()).playlists
    }.getOrDefault(mutableListOf()).toMutableList()

    private fun save(list: List<UserPlaylist>) {
        runCatching { file.writeText(json.encodeToString(PlaylistFile.serializer(), PlaylistFile(list))) }
    }

    @Synchronized
    fun list(): List<UserPlaylist> = load()

    @Synchronized
    fun create(name: String, keyword: String? = null): UserPlaylist {
        val list = load()
        val pl = UserPlaylist(
            id = "pl-" + java.lang.Long.toString(System.currentTimeMillis(), 36) + "-" +
                java.lang.Long.toString((0..46655).random().toLong(), 36),
            name = name.trim().ifEmpty { "未命名歌单" },
            createdAt = System.currentTimeMillis(),
            keyword = keyword,
            songs = emptyList()
        )
        list.add(0, pl)
        save(list)
        return pl
    }

    @Synchronized
    fun rename(id: String, name: String) {
        val list = load()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) { list[idx] = list[idx].copy(name = name.trim().ifEmpty { list[idx].name }); save(list) }
    }

    @Synchronized
    fun delete(id: String) = save(load().filter { it.id != id })

    @Synchronized
    fun addSong(id: String, song: Song): Pair<Boolean, String> {
        val list = load()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return false to "歌单不存在"
        if (list[idx].songs.any { it.key == song.key }) return false to "已在歌单中"
        list[idx] = list[idx].copy(songs = list[idx].songs + song)
        save(list)
        return true to "已加入歌单"
    }

    @Synchronized
    fun removeSong(id: String, songKey: String) {
        val list = load()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            list[idx] = list[idx].copy(songs = list[idx].songs.filter { it.key != songKey })
            save(list)
        }
    }

    @Synchronized
    fun saveFromSearch(name: String, keyword: String, songs: List<Song>): UserPlaylist {
        val pl = create(name, keyword)
        val list = load()
        val idx = list.indexOfFirst { it.id == pl.id }
        if (idx >= 0) { list[idx] = list[idx].copy(songs = songs); save(list) }
        return pl.copy(songs = songs)
    }

    @Synchronized
    fun replaceSongs(id: String, songs: List<Song>) {
        val list = load()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) { list[idx] = list[idx].copy(songs = songs); save(list) }
    }
}
