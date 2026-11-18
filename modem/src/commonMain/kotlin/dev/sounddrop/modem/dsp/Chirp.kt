package dev.sounddrop.modem.dsp

import kotlin.math.PI
import kotlin.math.sin

/** Linear frequency sweep used as the synchronisation preamble. */
public object Chirp {
    private const val TAPER_FRACTION = 0.1

    /**
     * A Tukey-tapered linear chirp from [startHz] to [endHz] lasting [length] samples, with peak
     * [amplitude].
     */
    public fun linear(
        length: Int,
        sampleRate: Int,
        startHz: Double,
        endHz: Double,
        amplitude: Double = 1.0,
    ): FloatArray {
        val duration = length.toDouble() / sampleRate
        val sweepRate = (endHz - startHz) / duration
        return FloatArray(length) { i ->
            val t = i.toDouble() / sampleRate
            val phase = 2 * PI * (startHz * t + sweepRate * t * t / 2)
            (amplitude * Windows.tukey(i, length, TAPER_FRACTION) * sin(phase)).toFloat()
        }
    }
}
