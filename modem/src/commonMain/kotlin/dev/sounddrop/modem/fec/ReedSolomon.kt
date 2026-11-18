package dev.sounddrop.modem.fec

import dev.sounddrop.modem.fec.GaloisField.alphaPow
import dev.sounddrop.modem.fec.GaloisField.mul

/**
 * Systematic Reed–Solomon code over GF(256) with [paritySize] parity bytes.
 *
 * Codewords are `data + parity` and may be shortened to any length up to 255 bytes. The decoder
 * corrects any combination of `e` errors and `s` erasures (known-bad positions) with
 * `2e + s <= paritySize`.
 *
 * Byte index `k` of an `n`-byte codeword is the coefficient of `x^(n - 1 - k)`; the generator
 * polynomial has the roots `α^0 .. α^(paritySize - 1)`.
 */
public class ReedSolomon(
    public val paritySize: Int,
) {
    init {
        require(paritySize in 1 until GaloisField.ORDER) { "paritySize must be in 1..254" }
    }

    /** Generator polynomial, highest degree first, monic. */
    private val generator: IntArray =
        run {
            var g = intArrayOf(1)
            for (i in 0 until paritySize) g = GaloisField.mulPoly(g, intArrayOf(alphaPow(i), 1))
            g.reversedArray()
        }

    /** Returns [data] followed by [paritySize] parity bytes. */
    public fun encode(data: ByteArray): ByteArray {
        require(data.size + paritySize <= GaloisField.ORDER) { "Codeword longer than 255 bytes" }
        val work = IntArray(data.size + paritySize)
        for (i in data.indices) work[i] = data[i].toInt() and 0xFF
        for (i in data.indices) {
            val coef = work[i]
            if (coef == 0) continue
            for (j in 1 until generator.size) work[i + j] = work[i + j] xor mul(generator[j], coef)
        }
        val out = data.copyOf(work.size)
        for (i in data.size until work.size) out[i] = work[i].toByte()
        return out
    }

    /**
     * Decodes [codeword] without modifying it.
     *
     * @param erasures byte indices that are known (or suspected) to be wrong.
     * @return the corrected codeword with the positions that were changed, or `null` when the
     * damage exceeds the correction capacity.
     */
    public fun decode(
        codeword: ByteArray,
        erasures: Collection<Int> = emptyList(),
    ): Result? {
        val n = codeword.size
        require(n in paritySize + 1..GaloisField.ORDER) { "Invalid codeword length $n" }
        val erased = erasures.filter { it in 0 until n }.distinct()
        if (erased.size > paritySize) return null

        val received = IntArray(n) { codeword[it].toInt() and 0xFF }
        val syndromes = syndromes(received)
        if (syndromes.all { it == 0 }) return Result(codeword.copyOf(), emptyList())

        val erasureLocator =
            erased.fold(intArrayOf(1)) { acc, k ->
                GaloisField.mulPoly(acc, intArrayOf(1, alphaPow(n - 1 - k)))
            }
        val locator = berlekampMassey(syndromes, erasureLocator, erased.size) ?: return null
        val positions = chienSearch(locator, n) ?: return null
        val evaluator = GaloisField.mulPoly(syndromes, locator).copyOf(paritySize)

        for (k in positions) {
            val x = alphaPow(n - 1 - k)
            val xInv = GaloisField.inverse(x)
            val derivative = formalDerivativeAt(locator, xInv)
            if (derivative == 0) return null
            val magnitude = GaloisField.div(mul(x, GaloisField.evalLowFirst(evaluator, xInv)), derivative)
            received[k] = received[k] xor magnitude
        }
        if (syndromes(received).any { it != 0 }) return null
        return Result(ByteArray(n) { received[it].toByte() }, positions.sorted())
    }

    private fun syndromes(received: IntArray): IntArray =
        IntArray(paritySize) { j ->
            // Horner over the codeword, highest degree first.
            val x = alphaPow(j)
            var y = 0
            for (c in received) y = mul(y, x) xor c
            y
        }

    /**
     * Berlekamp–Massey initialised with the erasure locator, so the result is the combined errata
     * locator (lowest degree first), or `null` if too many errors are present.
     */
    private fun berlekampMassey(
        syndromes: IntArray,
        erasureLocator: IntArray,
        erasureCount: Int,
    ): IntArray? {
        var lambda = erasureLocator.copyOf()
        var previous = erasureLocator.copyOf()
        var length = erasureCount
        for (k in erasureCount until paritySize) {
            var delta = 0
            for (i in lambda.indices) {
                if (k - i < 0) break
                delta = delta xor mul(lambda[i], syndromes[k - i])
            }
            previous = intArrayOf(0) + previous
            if (delta == 0) continue
            val updated = IntArray(maxOf(lambda.size, previous.size))
            for (i in lambda.indices) updated[i] = lambda[i]
            for (i in previous.indices) updated[i] = updated[i] xor mul(delta, previous[i])
            if (2 * length <= k + erasureCount) {
                length = k + 1 + erasureCount - length
                val inv = GaloisField.inverse(delta)
                previous = IntArray(lambda.size) { mul(lambda[it], inv) }
            }
            lambda = updated
        }
        val trimmed = lambda.copyOf(lambda.indexOfLast { it != 0 } + 1)
        val degree = trimmed.size - 1
        if (degree != length || 2 * (degree - erasureCount) + erasureCount > paritySize) return null
        return trimmed
    }

    /** Finds the byte indices whose locator `α^(n-1-k)` is a root of the inverse locator. */
    private fun chienSearch(
        locator: IntArray,
        n: Int,
    ): List<Int>? {
        val degree = locator.size - 1
        val positions =
            (0 until n).filter { k ->
                GaloisField.evalLowFirst(locator, alphaPow(-(n - 1 - k))) == 0
            }
        return positions.takeIf { it.size == degree }
    }

    private fun formalDerivativeAt(
        poly: IntArray,
        x: Int,
    ): Int {
        var y = 0
        var xPow = 1
        val xSquared = mul(x, x)
        // In characteristic 2 only odd-degree terms survive: Λ'(x) = Σ Λ_(2i+1) x^(2i).
        for (i in 1 until poly.size step 2) {
            y = y xor mul(poly[i], xPow)
            xPow = mul(xPow, xSquared)
        }
        return y
    }

    /** A successfully decoded codeword and the byte indices that had to be corrected. */
    public class Result(
        public val codeword: ByteArray,
        public val correctedPositions: List<Int>,
    )
}
