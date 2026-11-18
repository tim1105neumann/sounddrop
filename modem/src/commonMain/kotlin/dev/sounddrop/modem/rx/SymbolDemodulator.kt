package dev.sounddrop.modem.rx

import dev.sounddrop.modem.dsp.Goertzel
import dev.sounddrop.modem.dsp.Windows
import dev.sounddrop.modem.protocol.FrameLayout
import dev.sounddrop.modem.protocol.GrayCode
import dev.sounddrop.modem.protocol.ModemProfile
import dev.sounddrop.modem.protocol.TonePlan

/**
 * What the receiver heard during one symbol.
 *
 * @property value the decoded byte.
 * @property toneA index of the strongest tone in band A.
 * @property toneB index of the strongest tone in band B.
 * @property confidence power ratio between the strongest and second-strongest tone of the weaker
 * band; values near 1 mean the symbol is ambiguous.
 * @property erased whether the symbol is too uncertain and is handed to Reed–Solomon as an erasure.
 * @property powersA relative power of every band A tone, normalised so the strongest is 1.
 * @property powersB relative power of every band B tone, normalised so the strongest is 1.
 */
public class SymbolReading(
    public val value: Int,
    public val toneA: Int,
    public val toneB: Int,
    public val confidence: Float,
    public val erased: Boolean,
    public val powersA: FloatArray,
    public val powersB: FloatArray,
)

/** Measures the 32 tone powers of a symbol and picks the strongest tone in each band. */
public class SymbolDemodulator(
    private val sampleRate: Int,
) {
    private val layout = FrameLayout(sampleRate)
    private val windows = ModemProfile.entries.associateWith { Windows.hann(layout.windowSamples(it)) }

    /**
     * Demodulates the symbol whose first sample (including the guard) is `samples[symbolStart]`.
     * The caller must make sure the whole symbol is inside [samples].
     */
    public fun demodulate(
        samples: FloatArray,
        symbolStart: Int,
        profile: ModemProfile,
    ): SymbolReading {
        val from = symbolStart + layout.guardSamples(profile)
        val length = layout.windowSamples(profile)
        val window = windows.getValue(profile)
        val powersA =
            FloatArray(TonePlan.TONES_PER_BAND) { tone ->
                Goertzel.power(samples, from, length, TonePlan.bandA(tone), sampleRate, window).toFloat()
            }
        val powersB =
            FloatArray(TonePlan.TONES_PER_BAND) { tone ->
                Goertzel.power(samples, from, length, TonePlan.bandB(tone), sampleRate, window).toFloat()
            }
        val (toneA, ratioA) = strongest(powersA)
        val (toneB, ratioB) = strongest(powersB)
        val confidence = minOf(ratioA, ratioB)
        return SymbolReading(
            value = (GrayCode.valueOf(toneA) shl 4) or GrayCode.valueOf(toneB),
            toneA = toneA,
            toneB = toneB,
            confidence = confidence,
            erased = confidence < ERASURE_RATIO,
            powersA = normalised(powersA),
            powersB = normalised(powersB),
        )
    }

    private fun strongest(powers: FloatArray): Pair<Int, Float> {
        var best = 0
        for (i in powers.indices) if (powers[i] > powers[best]) best = i
        var second = 0f
        for (i in powers.indices) if (i != best && powers[i] > second) second = powers[i]
        val ratio = if (second <= 0f) Float.MAX_VALUE else powers[best] / second
        return best to ratio
    }

    private fun normalised(powers: FloatArray): FloatArray {
        val max = powers.max()
        return if (max <= 0f) powers else FloatArray(powers.size) { powers[it] / max }
    }

    private companion object {
        /** Below this best/second power ratio a symbol is treated as an erasure. */
        const val ERASURE_RATIO = 2f
    }
}
