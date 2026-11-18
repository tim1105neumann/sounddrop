package dev.sounddrop.modem.protocol

/**
 * The frequencies used on air: two bands of 16 tones each. A symbol plays exactly one tone from
 * each band at the same time; band A carries the high nibble and band B the low nibble.
 *
 * The 93.75 Hz spacing is a whole multiple of 1 / window length for both profiles, so the tones
 * are orthogonal and do not leak into each other's analysis bins.
 */
public object TonePlan {
    /** Number of tones per band (one nibble). */
    public const val TONES_PER_BAND: Int = 16

    /** Distance between neighbouring tones. */
    public const val SPACING_HZ: Double = 93.75

    /** Lowest tone of band A. */
    public const val BAND_A_BASE_HZ: Double = 1875.0

    /** Lowest tone of band B, two spacings above the top of band A. */
    public const val BAND_B_BASE_HZ: Double = BAND_A_BASE_HZ + (TONES_PER_BAND + 1) * SPACING_HZ

    /** Frequency of tone [index] in band A. */
    public fun bandA(index: Int): Double = BAND_A_BASE_HZ + index * SPACING_HZ

    /** Frequency of tone [index] in band B. */
    public fun bandB(index: Int): Double = BAND_B_BASE_HZ + index * SPACING_HZ

    /**
     * Frequency that carries [tone] for the high or low nibble of a byte played with [voicing].
     * Chords use band A and band B; melodies use the same scale for both nibbles.
     */
    public fun frequency(
        voicing: ModemProfile.Voicing,
        highNibble: Boolean,
        tone: Int,
    ): Double =
        when (voicing) {
            ModemProfile.Voicing.Chord -> if (highNibble) bandA(tone) else bandB(tone)
            ModemProfile.Voicing.Melody -> MelodicScale.note(tone)
        }

    /** Highest frequency the chord symbols use. */
    public val maxFrequencyHz: Double get() = bandB(TONES_PER_BAND - 1)
}
