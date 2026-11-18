package dev.sounddrop.modem.tx

import dev.sounddrop.modem.dsp.Chirp
import dev.sounddrop.modem.fec.Crc32
import dev.sounddrop.modem.fec.ReedSolomon
import dev.sounddrop.modem.protocol.FrameHeader
import dev.sounddrop.modem.protocol.FrameLayout
import dev.sounddrop.modem.protocol.GrayCode
import dev.sounddrop.modem.protocol.MelodicScale
import dev.sounddrop.modem.protocol.ModemProfile
import dev.sounddrop.modem.protocol.TonePlan
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Turns bytes into audio.
 *
 * A frame is `chirp | gap | header symbols | payload symbols | tail`. Each symbol is one byte,
 * played as two simultaneous tones (see [TonePlan]). The two tone generators keep their phase
 * across symbol boundaries, so changing frequency never produces an audible click.
 *
 * [ModemProfile.Melodic] payloads are played as two plucked notes per byte instead (see
 * [MelodicScale]); every note fades in and out, so there is nothing to click.
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
        if (profile.voicing == ModemProfile.Voicing.Melody) fadeOut(out, layout.payloadStart)
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
            val freqA = TonePlan.frequency(profile.voicing, highNibble = true, GrayCode.toneFor(value shr 4))
            val freqB = TonePlan.frequency(profile.voicing, highNibble = false, GrayCode.toneFor(value and 0x0F))
            when (profile.voicing) {
                ModemProfile.Voicing.Chord -> for (i in from until to) out[i] = oscillators.next(freqA, freqB)

                ModemProfile.Voicing.Melody -> {
                    val second = from + layout.slotSamples(profile)
                    writeNote(out, from, second, freqA)
                    writeNote(out, second, to, freqB)
                }
            }
        }
        return start + layout.symbolOffset(symbols.size, profile)
    }

    /** A single plucked note: quick attack, gentle decay and a short release, like a music box. */
    private fun writeNote(
        out: FloatArray,
        from: Int,
        to: Int,
        frequency: Double,
    ) {
        val length = to - from
        val attack = (NOTE_ATTACK_SECONDS * sampleRate).toInt()
        val release = (NOTE_RELEASE_SECONDS * sampleRate).toInt()
        for (i in 0 until length) {
            val t = i.toDouble() / sampleRate
            val edge =
                when {
                    i < attack -> rampGain(i, attack).toDouble()
                    i >= length - release -> rampGain(length - 1 - i, release).toDouble()
                    else -> 1.0
                }
            out[from + i] = (PEAK * edge * exp(-t / NOTE_DECAY_SECONDS) * sin(2 * PI * frequency * t)).toFloat()
        }
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
        private const val NOTE_ATTACK_SECONDS = 0.003
        private const val NOTE_RELEASE_SECONDS = 0.01
        private const val NOTE_DECAY_SECONDS = 0.15

        /** Appends the CRC-32 and Reed–Solomon parity to [payload]. */
        public fun encodePayload(payload: ByteArray): ByteArray {
            val crc = Crc32.compute(payload)
            val withCrc = payload + ByteArray(FrameHeader.CRC_SIZE) { (crc ushr (24 - 8 * it)).toByte() }
            return ReedSolomon(FrameHeader.payloadParity(payload.size)).encode(withCrc)
        }
    }
}
