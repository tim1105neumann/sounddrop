package dev.sounddrop.modem.protocol

import kotlin.math.roundToInt

/**
 * Where each part of a frame sits in time, converted to sample offsets for one [sampleRate].
 *
 * The transmitter and the receiver both derive their timing from this class, which keeps them in
 * agreement even when they run at different sample rates.
 */
public class FrameLayout(
    public val sampleRate: Int,
) {
    /** Length of the synchronisation chirp. */
    public val preambleSamples: Int = samples(PREAMBLE_SECONDS)

    /** Offset of the first header symbol from the start of the preamble. */
    public val headerStart: Int = samples(PREAMBLE_SECONDS + GAP_SECONDS)

    /** Offset of the first payload symbol from the start of the preamble. */
    public val payloadStart: Int get() = headerStart + symbolOffset(FrameHeader.SIZE, HEADER_PROFILE)

    /** Start of symbol [index] relative to its section, for [profile]. */
    public fun symbolOffset(
        index: Int,
        profile: ModemProfile,
    ): Int = samples(index * profile.symbolSeconds)

    /** Samples skipped at the start of every tone slot of [profile]. */
    public fun guardSamples(profile: ModemProfile): Int = samples(profile.guardSeconds)

    /** Start of the second tone slot of a symbol, relative to the symbol start. */
    public fun slotSamples(profile: ModemProfile): Int = samples(profile.slotSeconds)

    /** Samples analysed per tone slot of [profile]. */
    public fun windowSamples(profile: ModemProfile): Int = samples(profile.windowSeconds)

    /** Total frame length for a payload of [length] bytes, including the trailing tail. */
    public fun frameSamples(
        profile: ModemProfile,
        length: Int,
    ): Int = payloadStart + symbolOffset(payloadSymbols(length), profile) + samples(TAIL_SECONDS)

    private fun samples(seconds: Double): Int = (seconds * sampleRate).roundToInt()

    public companion object {
        /** Duration of the rising chirp the receiver synchronises on. */
        public const val PREAMBLE_SECONDS: Double = 0.2

        /** Lowest frequency of the chirp. */
        public const val CHIRP_START_HZ: Double = 1500.0

        /** Highest frequency of the chirp. */
        public const val CHIRP_END_HZ: Double = 5000.0

        /** Silence after the chirp so that its echo dies down before the header. */
        public const val GAP_SECONDS: Double = 0.03

        /** Short silence after the last symbol. */
        public const val TAIL_SECONDS: Double = 0.03

        /** The header always uses the most robust timing. */
        public val HEADER_PROFILE: ModemProfile = ModemProfile.Robust

        /** Symbols that carry a payload of [length] bytes: data, CRC and parity. */
        public fun payloadSymbols(length: Int): Int = length + FrameHeader.CRC_SIZE + FrameHeader.payloadParity(length)

        /** Air time of a complete frame carrying [length] bytes. */
        public fun airtimeSeconds(
            profile: ModemProfile,
            length: Int,
        ): Double =
            PREAMBLE_SECONDS + GAP_SECONDS + FrameHeader.SIZE * HEADER_PROFILE.symbolSeconds +
                payloadSymbols(length) * profile.symbolSeconds + TAIL_SECONDS
    }
}
