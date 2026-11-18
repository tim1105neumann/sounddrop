package dev.sounddrop.modem.tx

import dev.sounddrop.modem.dsp.Chirp
import dev.sounddrop.modem.fec.Crc32
import dev.sounddrop.modem.fec.ReedSolomon
import dev.sounddrop.modem.protocol.FrameHeader
import dev.sounddrop.modem.protocol.FrameLayout
import dev.sounddrop.modem.protocol.GrayCode
import dev.sounddrop.modem.protocol.ModemProfile
import dev.sounddrop.modem.protocol.TonePlan
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Turns bytes into audio.
 *
 * A frame is `chirp | gap | header symbols | payload symbols | tail`. Each symbol is one byte,
 * played as two simultaneous tones (see [TonePlan]). The two tone generators keep their phase
 * across symbol boundaries, so changing frequency never produces an audible click.
 */
public class Modulator(
    /** Sample rate of the generated audio. */
    public val sampleRate: Int,
) {
    private val layout = FrameLayout(sampleRate)

    /** Everything that goes on air for one frame, so it can be both played and visualised. */
    public class Transmission(
        /** The decoded form of the header. */
        public val header: FrameHeader,
        /** Header bytes including Reed–Solomon parity. */
        public val headerSymbols: ByteArray,
        /** Payload, CRC-32 and Reed–Solomon parity. */
        public val payloadSymbols: ByteArray,
        /** The audio, in `-1..1`. */
        public val samples: FloatArray,
    )

    /** Encodes [payload] of the given application-level [type] using [profile]. */
    public fun modulate(
        payload: ByteArray,
        type: Int,
        profile: ModemProfile = ModemProfile.Robust,
    ): Transmission {
        val header = FrameHeader(profile, type, payload.size)
        val headerSymbols = header.encode()
        val payloadSymbols = encodePayload(payload)

        val out = FloatArray(layout.frameSamples(profile, payload.size))
        Chirp
            .linear(
                length = layout.preambleSamples,
                sampleRate = sampleRate,
                startHz = FrameLayout.CHIRP_START_HZ,
                endHz = FrameLayout.CHIRP_END_HZ,
                amplitude = PEAK,
            ).copyInto(out)

        val oscillators = Oscillators()
        writeSymbols(out, layout.headerStart, headerSymbols, FrameLayout.HEADER_PROFILE, oscillators)
        fadeIn(out, layout.headerStart)
        val end = writeSymbols(out, layout.payloadStart, payloadSymbols, profile, oscillators)
        fadeOut(out, end)
        return Transmission(header, headerSymbols, payloadSymbols, out)
    }

    private fun writeSymbols(
        out: FloatArray,
        start: Int,
        symbols: ByteArray,
        profile: ModemProfile,
        oscillators: Oscillators,
    ): Int {
        for (index in symbols.indices) {
            val from = start + layout.symbolOffset(index, profile)
            val to = start + layout.symbolOffset(index + 1, profile)
            val value = symbols[index].toInt() and 0xFF
            val freqA = TonePlan.bandA(GrayCode.toneFor(value shr 4))
            val freqB = TonePlan.bandB(GrayCode.toneFor(value and 0x0F))
            for (i in from until to) out[i] = oscillators.next(freqA, freqB)
        }
        return start + layout.symbolOffset(symbols.size, profile)
    }

    private fun fadeIn(
        out: FloatArray,
        from: Int,
    ) {
        val ramp = rampSamples()
        for (i in 0 until ramp) out[from + i] *= rampGain(i, ramp)
    }

    private fun fadeOut(
        out: FloatArray,
        end: Int,
    ) {
        val ramp = rampSamples()
        for (i in 0 until ramp) out[end - 1 - i] *= rampGain(i, ramp)
    }

    private fun rampSamples(): Int = (RAMP_SECONDS * sampleRate).toInt()

    private fun rampGain(
        i: Int,
        ramp: Int,
    ): Float = (0.5 - 0.5 * cos(PI * i / ramp)).toFloat()

    /** Two phase-continuous sine generators, one per band. */
    private inner class Oscillators {
        private var phaseA = 0.0
        private var phaseB = 0.0

        fun next(
            freqA: Double,
            freqB: Double,
        ): Float {
            val sample = TONE_AMPLITUDE * (sin(phaseA) + sin(phaseB))
            phaseA = (phaseA + 2 * PI * freqA / sampleRate) % (2 * PI)
            phaseB = (phaseB + 2 * PI * freqB / sampleRate) % (2 * PI)
            return sample.toFloat()
        }
    }

    public companion object {
        private const val PEAK = 0.9
        private const val TONE_AMPLITUDE = PEAK / 2
        private const val RAMP_SECONDS = 0.004

        /** Appends the CRC-32 and Reed–Solomon parity to [payload]. */
        public fun encodePayload(payload: ByteArray): ByteArray {
            val crc = Crc32.compute(payload)
            val withCrc = payload + ByteArray(FrameHeader.CRC_SIZE) { (crc ushr (24 - 8 * it)).toByte() }
            return ReedSolomon(FrameHeader.payloadParity(payload.size)).encode(withCrc)
        }
    }
}
