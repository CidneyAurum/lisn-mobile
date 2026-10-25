package com.glass.lisn.player

import com.glass.lisn.model.Song
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Queue file codec, including the double-encoded queue used by earlier releases. */
internal data class PlaybackQueueSnapshot(val queue: List<Song>, val index: Int) {
    fun encode(): String = buildJsonObject {
        put("queue", codec.encodeToJsonElement(songListSerializer, queue))
        put("index", index)
    }.toString()

    companion object {
        private val codec = Json { ignoreUnknownKeys = true }
        private val songListSerializer = ListSerializer(Song.serializer())

        fun decode(raw: String): PlaybackQueueSnapshot {
            val obj = codec.parseToJsonElement(raw).jsonObject
            val stored = obj["queue"] ?: error("Missing queue")
            val queueElement = if (stored is JsonPrimitive && stored.isString) {
                codec.parseToJsonElement(stored.content)
            } else stored
            require(queueElement is JsonArray) { "Invalid queue" }
            val queue = codec.decodeFromJsonElement(songListSerializer, queueElement)
            val index = obj["index"]?.jsonPrimitive?.intOrNull ?: -1
            return PlaybackQueueSnapshot(queue, index.coerceIn(-1, queue.lastIndex))
        }

        /** A stale UI index must never select another song or prevent playback entirely. */
        fun select(song: Song, queue: List<Song>?, requestedIndex: Int): PlaybackQueueSnapshot {
            val songs = queue.orEmpty()
            val index = requestedIndex.takeIf { songs.getOrNull(it)?.key == song.key }
                ?: songs.indexOfFirst { it.key == song.key }
            return if (index >= 0) PlaybackQueueSnapshot(songs, index)
            else PlaybackQueueSnapshot(listOf(song), 0)
        }
    }
}
