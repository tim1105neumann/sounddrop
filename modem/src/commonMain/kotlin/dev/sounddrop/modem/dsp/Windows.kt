package dev.sounddrop.modem.dsp

import kotlin.math.PI
import kotlin.math.cos

/** Window functions used for spectral analysis and tapering. */
public object Windows {
    /** Periodic Hann window of [length] samples. */
    public fun hann(length: Int): FloatArray = FloatArray(length) { (0.5 - 0.5 * cos(2 * PI * it / length)).toFloat() }

    /**
     * Tukey (tapered cosine) gain at sample [i] of [length]; the first and last [taperFraction]/2
     * of the window are raised-cosine ramps.
     */
    public fun tukey(
        i: Int,
        length: Int,
        taperFraction: Double,
    ): Double {
        val ramp = taperFraction * length / 2
        return when {
            i < ramp -> 0.5 - 0.5 * cos(PI * i / ramp)
            i > length - 1 - ramp -> 0.5 - 0.5 * cos(PI * (length - 1 - i) / ramp)
            else -> 1.0
        }
    }
}
