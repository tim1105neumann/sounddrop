package dev.sounddrop.modem.dsp

import kotlin.math.PI
import kotlin.math.cos

/**
 * Goertzel filter: the power of a single frequency in a block of samples.
 *
 * Unlike an FFT bin this works at any exact frequency, which is what lets the modem decode
 * 44.1 kHz and 48 kHz audio without resampling.
 */
public object Goertzel {
    /**
     * Power of [frequency] Hz in `samples[from until from + length]`, after multiplying by [window]
     * (which must have [length] entries).
     */
    public fun power(
        samples: FloatArray,
        from: Int,
        length: Int,
        frequency: Double,
        sampleRate: Int,
        window: FloatArray,
    ): Double {
        val coeff = 2 * cos(2 * PI * frequency / sampleRate)
        var s1 = 0.0
        var s2 = 0.0
        for (i in 0 until length) {
            val s0 = samples[from + i] * window[i] + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }
        return s1 * s1 + s2 * s2 - coeff * s1 * s2
    }
}
