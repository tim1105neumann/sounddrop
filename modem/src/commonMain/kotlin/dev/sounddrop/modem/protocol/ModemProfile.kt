package dev.sounddrop.modem.protocol

/**
 * Symbol timing of a transmission. Every value is in seconds so the same profile works at any
 * sample rate.
 *
 * Each symbol consists of [Voicing.slots] tone slots. A slot starts with a [guardSeconds] gap that
 * the receiver ignores (it absorbs room echo of the previous tone), followed by the
 * [windowSeconds] part that is analysed.
 */
public enum class ModemProfile(
    public val id: Int,
    public val guardSeconds: Double,
    public val windowSeconds: Double,
    /** How the two nibbles of a byte are turned into tones. */
    public val voicing: Voicing = Voicing.Chord,
) {
    /** Default: long symbols that survive echoey rooms and a few metres of distance. 125 bit/s. */
    Robust(id = 0, guardSeconds = 0.0213333, windowSeconds = 0.0426667),

    /** Half the symbol length for devices close to each other. 250 bit/s. */
    Fast(id = 1, guardSeconds = 0.0106667, windowSeconds = 0.0213333),

    /** Every byte is two plucked notes of [MelodicScale], so a transfer sounds like a tune. 50 bit/s. */
    Melodic(id = 2, guardSeconds = 0.02, windowSeconds = 0.06, voicing = Voicing.Melody),
    ;

    /** Duration of one tone slot: the guard followed by the analysed window. */
    public val slotSeconds: Double get() = guardSeconds + windowSeconds

    /** Duration of a single one-byte symbol. */
    public val symbolSeconds: Double get() = voicing.slots * slotSeconds

    /** Raw throughput before error correction. */
    public val bitsPerSecond: Double get() = BITS_PER_SYMBOL / symbolSeconds

    /** How a byte is played. */
    public enum class Voicing(
        /** Tone slots per symbol. */
        public val slots: Int,
    ) {
        /** Both nibbles at once: one tone from each band of [TonePlan]. */
        Chord(slots = 1),

        /** One note of [MelodicScale] per nibble, the high nibble first. */
        Melody(slots = 2),
    }

    public companion object {
        /** Every symbol carries one byte: a nibble in each of the two tone bands. */
        public const val BITS_PER_SYMBOL: Int = 8

        /** Looks up a profile by its on-air [id], or `null` if unknown. */
        public fun fromId(id: Int): ModemProfile? = entries.firstOrNull { it.id == id }
    }
}
