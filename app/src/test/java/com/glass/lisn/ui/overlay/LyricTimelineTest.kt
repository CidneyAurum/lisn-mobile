package com.glass.lisn.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricTimelineTest {
    @Test fun emptyAndBeforeTheFirstLineHaveNoActiveLyric() {
        assertEquals(-1, lyricLineIndex(longArrayOf(), 500))
        assertEquals(-1, lyricLineIndex(longArrayOf(1000, 2000), 999))
    }

    @Test fun exactTimestampUsesTheLastMatchingLine() {
        val times = longArrayOf(1000, 2000, 2000, 5000)
        assertEquals(0, lyricLineIndex(times, 1000))
        assertEquals(2, lyricLineIndex(times, 2000))
        assertEquals(2, lyricLineIndex(times, 4999))
        assertEquals(3, lyricLineIndex(times, 9999))
    }

    @Test fun seekingBothDirectionsSelectsTheCorrectLineInALargeTimeline() {
        val times = LongArray(50_000) { it * 1000L }
        assertEquals(49_999, lyricLineIndex(times, 49_999_999))
        assertEquals(2, lyricLineIndex(times, 2500))
        assertEquals(0, lyricLineIndex(times, 0))
    }
}
