package dev.sounddrop.modem.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolTest {
    @Test
    fun grayCodeRoundTripsAndNeighboursDifferInOneBit() {
        for (value in 0 until 16) assertEquals(value, GrayCode.valueOf(GrayCode.toneFor(value)))
        for (tone in 0 until 15) {
            val diff = GrayCode.valueOf(tone) xor GrayCode.valueOf(tone + 1)
            assertEquals(1, diff.countOneBits())
        }
    }

    @Test
    fun headerRoundTripsAndSurvivesTwoErrors() {
        val header = FrameHeader(ModemProfile.Fast, type = 7, length = 123)
        val bytes = header.encode()
        bytes[0] = (bytes[0].toInt() xor 0x55).toByte()
        bytes[5] = (bytes[5].toInt() xor 0x0F).toByte()
        assertEquals(header, FrameHeader.decode(bytes))
    }

    @Test
    fun garbageIsNotAHeader() {
        assertNull(FrameHeader.decode(byteArrayOf(0x70, 1, 2, 3, 4, 5, 6)))
    }

    @Test
    fun largestFrameFitsInOneCodeword() {
        assertTrue(FrameLayout.payloadSymbols(FrameHeader.MAX_PAYLOAD) <= 255)
    }

    @Test
    fun tonesStayInsideTheChirpBand() {
        assertTrue(TonePlan.bandA(0) >= FrameLayout.CHIRP_START_HZ)
        assertTrue(TonePlan.maxFrequencyHz <= FrameLayout.CHIRP_END_HZ)
        assertTrue(TonePlan.bandB(0) > TonePlan.bandA(TonePlan.TONES_PER_BAND - 1))
    }

    @Test
    fun melodicHeaderRoundTrips() {
        val header = FrameHeader(ModemProfile.Melodic, type = 0, length = 42)
        assertEquals(header, FrameHeader.decode(header.encode()))
    }

    @Test
    fun melodicScaleIsThreeOctavesOfPentatonicNotes() {
        val notes = (0 until MelodicScale.NOTES).map(MelodicScale::note)
        assertEquals(523.25, notes.first(), 0.01)
        assertEquals(8.0, notes.last() / notes.first(), 1e-9)
        assertEquals(2.0, notes[5] / notes[0], 1e-9)
        // No semitones: neighbouring notes are at least a whole tone apart.
        notes.zipWithNext().forEach { (low, high) -> assertTrue(high / low > 1.12) }
    }

    @Test
    fun melodicSymbolIsTwoNotes() {
        assertEquals(2 * ModemProfile.Melodic.slotSeconds, ModemProfile.Melodic.symbolSeconds)
        assertEquals(ModemProfile.Robust.slotSeconds, ModemProfile.Robust.symbolSeconds)
    }
}
