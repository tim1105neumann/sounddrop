package dev.sounddrop.modem.rx

import dev.sounddrop.modem.dsp.Chirp
import dev.sounddrop.modem.dsp.Fft
import dev.sounddrop.modem.protocol.FrameLayout
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Finds the start of the preamble chirp with an FFT-based matched filter (overlap-save
 * cross-correlation), normalised by signal energy so loudness does not matter.
 */
internal class PreambleDetector(
    sampleRate: Int,
) {
    private val template =
        Chirp.linear(
            length = FrameLayout(sampleRate).preambleSamples,
            sampleRate = sampleRate,
            startHz = FrameLayout.CHIRP_START_HZ,
            endHz = FrameLayout.CHIRP_END_HZ,
        )
    private val m = template.size
    private val n = Fft.nextPowerOfTwo(2 * m)
    private val fft = Fft(n)
    private val validLags = n - m + 1
    private val templateEnergy = template.sumOf { it.toDouble() * it }
    private val templateRe = DoubleArray(n)
    private val templateIm = DoubleArray(n)
    private val re = DoubleArray(n)
    private val im = DoubleArray(n)
    private val prefixEnergy = DoubleArray(n + 1)

    init {
        for (i in template.indices) templateRe[i] = template[i].toDouble()
        fft.forward(templateRe, templateIm)
    }

    /** Result of scanning the buffer. */
    sealed interface Scan {
        /** The preamble starts at absolute sample [start]; [score] is its normalised correlation. */
        data class Found(
            val start: Long,
            val score: Float,
        ) : Scan

        /** Nothing found yet; continue scanning from [nextFrom] once more audio arrived. */
        data class NotYet(
            val nextFrom: Long,
        ) : Scan
    }

    /** Scans [buffer] starting at absolute index [from] as far as the available audio allows. */
    fun scan(
        buffer: SampleBuffer,
        from: Long,
    ): Scan {
        var blockStart = maxOf(from, buffer.start)
        while (buffer.contains(blockStart, n)) {
            correlate(buffer, blockStart)
            val first = (0 until validLags).firstOrNull { score(it) >= THRESHOLD }
            if (first == null) {
                blockStart += validLags
                continue
            }
            val searchEnd = first + m / 2
            if (searchEnd >= validLags) {
                blockStart += first
                continue
            }
            val best = (first..searchEnd).maxBy { score(it) }
            return Scan.Found(blockStart + best, score(best).toFloat())
        }
        return Scan.NotYet(blockStart)
    }

    private fun correlate(
        buffer: SampleBuffer,
        blockStart: Long,
    ) {
        val offset = buffer.offsetOf(blockStart)
        val data = buffer.data
        for (i in 0 until n) {
            val x = data[offset + i].toDouble()
            re[i] = x
            im[i] = 0.0
            prefixEnergy[i + 1] = prefixEnergy[i] + x * x
        }
        fft.forward(re, im)
        for (i in 0 until n) {
            // Multiply by the conjugate template spectrum: correlation instead of convolution.
            val r = re[i] * templateRe[i] + im[i] * templateIm[i]
            val j = im[i] * templateRe[i] - re[i] * templateIm[i]
            re[i] = r
            im[i] = j
        }
        fft.inverse(re, im)
    }

    private fun score(lag: Int): Double {
        val energy = prefixEnergy[lag + m] - prefixEnergy[lag]
        if (energy < MIN_ENERGY) return 0.0
        return abs(re[lag]) / sqrt(templateEnergy * energy)
    }

    private companion object {
        /** Normalised correlation needed to accept a preamble. Pure noise stays well below 0.1. */
        const val THRESHOLD = 0.3
        const val MIN_ENERGY = 1e-6
    }
}
