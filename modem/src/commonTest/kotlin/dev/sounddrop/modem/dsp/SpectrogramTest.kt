package dev.sounddrop.modem.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpectrogramTest {
    @Test
    fun loudestBinMatchesTheToneFrequency() {
        val rate = 48_000
        val samples = FloatArray(Spectrogram.FFT_SIZE * 3) { sin(2 * PI * 3000.0 * it / rate).toFloat() * 0.5f }
        val spectrogram = Spectrogram(rate)
        val columns = spectrogram.feed(samples)
        assertEquals(3, columns.size)
        val loudest = columns[1].indices.maxBy { columns[1][it] }
        val hz = spectrogram.frequencyOf(loudest)
        assertTrue(abs(hz - 3000.0) < 30.0, "loudest bin at $hz Hz")
    }

    @Test
    fun partialColumnsWaitForMoreSamples() {
        val spectrogram = Spectrogram(44_100)
        assertEquals(0, spectrogram.feed(FloatArray(1000)).size)
        assertEquals(1, spectrogram.feed(FloatArray(100)).size)
    }
}
