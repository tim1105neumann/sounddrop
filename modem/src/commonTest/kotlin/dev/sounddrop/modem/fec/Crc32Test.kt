package dev.sounddrop.modem.fec

import kotlin.test.Test
import kotlin.test.assertEquals

class Crc32Test {
    @Test
    fun matchesStandardCheckValue() {
        assertEquals(0xCBF43926.toInt(), Crc32.compute("123456789".encodeToByteArray()))
    }

    @Test
    fun emptyInputIsZero() {
        assertEquals(0, Crc32.compute(ByteArray(0)))
    }
}
