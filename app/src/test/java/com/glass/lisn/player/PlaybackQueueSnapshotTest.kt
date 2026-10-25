package com.glass.lisn.player

import com.glass.lisn.model.Song
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackQueueSnapshotTest {
    private val a = Song("a", "甲", "歌手")
    private val b = Song("b", "乙", "歌手")

    @Test fun restoresLegacyDoubleEncodedQueueWithoutLosingSongs() {
        val oldFile = buildJsonObject {
            put("queue", Json.encodeToString(ListSerializer(Song.serializer()), listOf(a, b)))
            put("index", 1)
        }.toString()
        assertEquals(PlaybackQueueSnapshot(listOf(a, b), 1), PlaybackQueueSnapshot.decode(oldFile))
    }

    @Test fun roundTripsQueueAndClampsIndexAfterQueueWasShortened() {
        val snapshot = PlaybackQueueSnapshot(listOf(a, b), 1)
        assertEquals(snapshot, PlaybackQueueSnapshot.decode(snapshot.encode()))
        assertEquals(PlaybackQueueSnapshot(listOf(a), 0), PlaybackQueueSnapshot.decode(
            PlaybackQueueSnapshot(listOf(a), 20).encode()
        ))
        assertEquals(PlaybackQueueSnapshot(emptyList(), -1), PlaybackQueueSnapshot.decode(
            PlaybackQueueSnapshot(emptyList(), 20).encode()
        ))
    }

    @Test fun staleIndexStillPlaysTheSongTheUserSelected() {
        assertEquals(PlaybackQueueSnapshot(listOf(a, b), 1),
            PlaybackQueueSnapshot.select(b, listOf(a, b), 0))
        assertEquals(PlaybackQueueSnapshot(listOf(a, b), 1),
            PlaybackQueueSnapshot.select(b, listOf(a, b), 100))
    }

    @Test fun absentSongOrEmptyQueueFallsBackToSelectedSong() {
        assertEquals(PlaybackQueueSnapshot(listOf(b), 0), PlaybackQueueSnapshot.select(b, listOf(a), 0))
        assertEquals(PlaybackQueueSnapshot(listOf(b), 0), PlaybackQueueSnapshot.select(b, emptyList(), -1))
    }
}
