package dev.sounddrop.modem.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * In-place iterative radix-2 complex FFT of a fixed power-of-two [size].
 *
 * Twiddle factors are precomputed once, so a single instance can be reused for many transforms.
 */
public class Fft(
    public val size: Int,
) {
    init {
        require(size >= 2 && size and (size - 1) == 0) { "FFT size must be a power of two, was $size" }
    }

    private val cosTable = DoubleArray(size / 2) { cos(2 * PI * it / size) }
    private val sinTable = DoubleArray(size / 2) { sin(2 * PI * it / size) }

    /** Forward transform (e^-iωt kernel) of the complex signal `re + i·im`, in place. */
    public fun forward(
        re: DoubleArray,
        im: DoubleArray,
    ): Unit = transform(re, im, inverse = false)

    /** Inverse transform, including the 1/N scaling, in place. */
    public fun inverse(
        re: DoubleArray,
        im: DoubleArray,
    ) {
        transform(re, im, inverse = true)
        for (i in 0 until size) {
            re[i] /= size
            im[i] /= size
        }
    }

    private fun transform(
        re: DoubleArray,
        im: DoubleArray,
        inverse: Boolean,
    ) {
        require(re.size == size && im.size == size) { "Expected arrays of length $size" }
        bitReverse(re, im)
        val sign = if (inverse) 1.0 else -1.0
        var half = 1
        while (half < size) {
            val step = size / (half * 2)
            for (start in 0 until size step half * 2) {
                for (k in 0 until half) {
                    val wr = cosTable[k * step]
                    val wi = sign * sinTable[k * step]
                    val a = start + k
                    val b = a + half
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
            }
            half *= 2
        }
    }

    private fun bitReverse(
        re: DoubleArray,
        im: DoubleArray,
    ) {
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
    }

    public companion object {
        /** Smallest power of two that is `>= n`. */
        public fun nextPowerOfTwo(n: Int): Int {
            var p = 1
            while (p < n) p = p shl 1
            return p
        }
    }
}
