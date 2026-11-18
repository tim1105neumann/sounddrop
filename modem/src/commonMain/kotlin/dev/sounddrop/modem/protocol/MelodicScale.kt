package dev.sounddrop.modem.protocol

import kotlin.math.pow

/**
 * The 16 notes of [ModemProfile.Melodic]: three octaves of the C major pentatonic scale
 * (C D E G A) starting at C5, plus C8 on top.
 *
 * A pentatonic scale has no semitone steps, so any sequence of its notes sounds consonant, even
 * one dictated by random data. The closest notes (C5 and D5) are 64 Hz apart, about four
 * analysis bins of a 60 ms window, so they are easy to tell apart.
 */
public object MelodicScale {
    /** Number of notes, one per nibble value. */
    public const val NOTES: Int = TonePlan.TONES_PER_BAND

    private const val C5_HZ = 523.2511
    private val PENTATONIC_STEPS = intArrayOf(0, 2, 4, 7, 9)

    /** Frequency of note [index], in equal temperament with A4 = 440 Hz. */
    public fun note(index: Int): Double {
        require(index in 0 until NOTES) { "note index must be in 0 until $NOTES, was $index" }
        val semitones = 12 * (index / PENTATONIC_STEPS.size) + PENTATONIC_STEPS[index % PENTATONIC_STEPS.size]
        return C5_HZ * 2.0.pow(semitones / 12.0)
    }
}
