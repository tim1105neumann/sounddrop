package dev.sounddrop.modem.dsp

import kotlin.math.log10

/**
 * Short-time Fourier transform for visualisation: turns a stream of samples into columns of
 * loudness values in `0..1`, one column per [FFT_SIZE] samples, covering [minHz]..[maxHz].
 */
public class Spectrogram(
    public val sampleRate: Int,
    public val minHz: Double = 1000.0,
    public val maxHz: Double = 6000.0,
) {
    private val fft = Fft(FFT_SIZE)
    private val window = Windows.hann(FFT_SIZE)
    private val re = DoubleArray(FFT_SIZE)
    private val im = DoubleArray(FFT_SIZE)
    private val pending = FloatArray(FFT_SIZE)
    private var pendingSize = 0
    private val firstBin = (minHz * FFT_SIZE / sampleRate).toInt()
    private val lastBin = (maxHz * FFT_SIZE / sampleRate).toInt().coerceAtMost(FFT_SIZE / 2)

    /** Number of values in each column; index 0 is [minHz]. */
    public val binCount: Int = lastBin - firstBin + 1

    /** Centre frequency of value [index] in a column. */
    public fun frequencyOf(index: Int): Double = (firstBin + index).toDouble() * sampleRate / FFT_SIZE

    /** Duration of audio represented by one column. */
    public val secondsPerColumn: Double get() = FFT_SIZE.toDouble() / sampleRate

    /** Adds [samples] and returns the columns that became complete. */
    public fun feed(samples: FloatArray): List<FloatArray> {
        val columns = mutableListOf<FloatArray>()
        for (sample in samples) {
            pending[pendingSize++] = sample
            if (pendingSize == FFT_SIZE) {
                columns += column()
                pendingSize = 0
            }
        }
        return columns
    }

    private fun column(): FloatArray {
        for (i in 0 until FFT_SIZE) {
            re[i] = (pending[i] * window[i]).toDouble()
            im[i] = 0.0
        }
        fft.forward(re, im)
        return FloatArray(binCount) { i ->
            val bin = firstBin + i
            // A full-scale sine through a Hann window peaks at (N / 4)^2.
            val power = (re[bin] * re[bin] + im[bin] * im[bin]) / FULL_SCALE_POWER
            val db = 10 * log10(power + 1e-12)
            ((db - FLOOR_DB) / -FLOOR_DB).toFloat().coerceIn(0f, 1f)
        }
    }

    public companion object {
        /** Samples per column. */
        public const val FFT_SIZE: Int = 1024
        private const val FULL_SCALE_POWER = (FFT_SIZE / 4.0) * (FFT_SIZE / 4.0)
        private const val FLOOR_DB = -80.0

        /** Spectrogram of a complete recording. */
        public fun of(
            samples: FloatArray,
            sampleRate: Int,
        ): List<FloatArray> = Spectrogram(sampleRate).feed(samples)
    }
}
