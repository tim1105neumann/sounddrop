package dev.sounddrop.modem.fec

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ReedSolomonTest {
    private val random = Random(42)

    @Test
    fun cleanCodewordDecodesUnchanged() {
        val rs = ReedSolomon(8)
        val codeword = rs.encode("hello".encodeToByteArray())
        val result = assertNotNull(rs.decode(codeword))
        assertContentEquals(codeword, result.codeword)
        assertEquals(emptyList(), result.correctedPositions)
    }

    @Test
    fun correctsUpToHalfTheParityInErrors() {
        repeat(200) {
            val parity = 2 * random.nextInt(1, 17)
            val rs = ReedSolomon(parity)
            val data = random.nextBytes(random.nextInt(1, 255 - parity))
            val codeword = rs.encode(data)
            val damaged = corrupt(codeword, positions = pick(codeword.size, parity / 2))
            val result = assertNotNull(rs.decode(damaged), "parity=$parity size=${codeword.size}")
            assertContentEquals(codeword, result.codeword)
        }
    }

    @Test
    fun correctsMixesOfErrorsAndErasures() {
        repeat(200) {
            val parity = 2 * random.nextInt(2, 17)
            val rs = ReedSolomon(parity)
            val codeword = rs.encode(random.nextBytes(random.nextInt(1, 255 - parity)))
            val erasureCount = random.nextInt(0, parity + 1)
            val errorCount = (parity - erasureCount) / 2
            val positions = pick(codeword.size, erasureCount + errorCount)
            val damaged = corrupt(codeword, positions)
            val result = assertNotNull(rs.decode(damaged, positions.take(erasureCount)))
            assertContentEquals(codeword, result.codeword)
        }
    }

    @Test
    fun erasuresMayPointAtIntactBytes() {
        val rs = ReedSolomon(16)
        val codeword = rs.encode(random.nextBytes(100))
        val damaged = corrupt(codeword, listOf(3, 50))
        val result = assertNotNull(rs.decode(damaged, listOf(3, 7, 8, 9)))
        assertContentEquals(codeword, result.codeword)
    }

    @Test
    fun reportsFailureWhenDamageExceedsCapacity() {
        val rs = ReedSolomon(8)
        var failures = 0
        repeat(100) {
            val codeword = rs.encode(random.nextBytes(60))
            val result = rs.decode(corrupt(codeword, pick(codeword.size, 8)))
            if (result == null) {
                failures++
            } else {
                // A miscorrection to a different valid codeword is possible but must be rare.
                assertNotEquals(codeword, result.codeword)
            }
        }
        assertEquals(true, failures > 90, "failures=$failures")
    }

    @Test
    fun tooManyErasuresFail() {
        val rs = ReedSolomon(4)
        val codeword = rs.encode(byteArrayOf(1, 2, 3))
        assertNull(rs.decode(codeword, listOf(0, 1, 2, 3, 4)))
    }

    private fun assertNotEquals(
        expected: ByteArray,
        actual: ByteArray,
    ) = assertEquals(false, expected.contentEquals(actual))

    private fun pick(
        size: Int,
        count: Int,
    ): List<Int> = (0 until size).shuffled(random).take(count)

    private fun corrupt(
        codeword: ByteArray,
        positions: List<Int>,
    ): ByteArray {
        val copy = codeword.copyOf()
        for (p in positions) copy[p] = (copy[p].toInt() xor random.nextInt(1, 256)).toByte()
        return copy
    }
}
