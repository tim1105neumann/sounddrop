package dev.sounddrop.modem.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class DspTest {
    @Test
    fun fftMatchesNaiveDft() {
        val n = 64
        val random = Random(1)
        val inputRe = DoubleArray(n) { random.nextDouble(-1.0, 1.0) }
        val inputIm = DoubleArray(n) { random.nextDouble(-1.0, 1.0) }
        val re = inputRe.copyOf()
        val im = inputIm.copyOf()
        Fft(n).forward(re, im)
        for (k in 0 until n) {
            var sumRe = 0.0
            var sumIm = 0.0
            for (t in 0 until n) {
                val angle = -2 * PI * k * t / n
                sumRe += inputRe[t] * cos(angle) - inputIm[t] * sin(angle)
                sumIm += inputRe[t] * sin(angle) + inputIm[t] * cos(angle)
            }
            assertTrue(abs(sumRe - re[k]) < 1e-9 && abs(sumIm - im[k]) < 1e-9, "bin $k")
        }
    }

    @Test
    fun inverseRestoresInput() {
        val n = 256
        val original = DoubleArray(n) { sin(it * 0.3) }
        val re = original.copyOf()
        val im = DoubleArray(n)
        val fft = Fft(n)
        fft.forward(re, im)
        fft.inverse(re, im)
        for (i in 0 until n) assertTrue(abs(re[i] - original[i]) < 1e-9)
    }

    @Test
    fun goertzelPicksTheToneThatIsPresent() {
        val rate = 44_100
        val length = 1882
        val samples = FloatArray(length) { sin(2 * PI * 2531.25 * it / rate).toFloat() }
        val window = Windows.hann(length)
        val present = Goertzel.power(samples, 0, length, 2531.25, rate, window)
        val neighbour = Goertzel.power(samples, 0, length, 2625.0, rate, window)
        assertTrue(present > 100 * neighbour, "present=$present neighbour=$neighbour")
    }
}
