package com.glass.lisn.ui.overlay

/** Last timestamp at or before the position, including duplicate timestamps. */
internal fun lyricLineIndex(timestamps: LongArray, positionMs: Long): Int {
    var low = 0
    var high = timestamps.size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (timestamps[middle] <= positionMs) low = middle + 1 else high = middle
    }
    return low - 1
}
