package dev.sounddrop.modem.rx

/**
 * Growable sample history addressed by absolute sample index (counted since the stream started).
 * Old samples can be dropped with [discardBefore] to keep memory bounded.
 */
internal class SampleBuffer {
    var data = FloatArray(INITIAL_CAPACITY)
        private set

    /** Absolute index of `data[0]`. */
    var start = 0L
        private set

    private var size = 0

    /** Absolute index one past the newest sample. */
    val end: Long get() = start + size

    fun append(chunk: FloatArray) {
        if (size + chunk.size > data.size) data = data.copyOf(maxOf(data.size * 2, size + chunk.size))
        chunk.copyInto(data, size)
        size += chunk.size
    }

    /** Position of absolute index [absolute] inside [data]. */
    fun offsetOf(absolute: Long): Int = (absolute - start).toInt()

    fun contains(
        from: Long,
        length: Int,
    ): Boolean = from >= start && from + length <= end

    fun discardBefore(absolute: Long) {
        val drop = (absolute - start).coerceIn(0L, size.toLong()).toInt()
        if (drop == 0) return
        data.copyInto(data, 0, drop, size)
        size -= drop
        start += drop
    }

    private companion object {
        const val INITIAL_CAPACITY = 1 shl 16
    }
}
