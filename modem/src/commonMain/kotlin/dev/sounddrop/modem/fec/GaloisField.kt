package dev.sounddrop.modem.fec

/**
 * Arithmetic in GF(2^8) with the primitive polynomial x^8 + x^4 + x^3 + x^2 + 1 (0x11D)
 * and generator α = 2.
 *
 * Elements are represented as `Int` values in `0..255`. Addition and subtraction are both XOR.
 */
internal object GaloisField {
    private const val PRIMITIVE_POLY = 0x11D
    const val ORDER: Int = 255

    private val exp = IntArray(ORDER * 2)
    private val log = IntArray(ORDER + 1)

    init {
        var x = 1
        for (i in 0 until ORDER) {
            exp[i] = x
            log[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor PRIMITIVE_POLY
        }
        for (i in ORDER until ORDER * 2) exp[i] = exp[i - ORDER]
    }

    fun mul(
        a: Int,
        b: Int,
    ): Int = if (a == 0 || b == 0) 0 else exp[log[a] + log[b]]

    fun div(
        a: Int,
        b: Int,
    ): Int {
        require(b != 0) { "Division by zero in GF(256)" }
        return if (a == 0) 0 else exp[(log[a] + ORDER - log[b]) % ORDER]
    }

    fun inverse(a: Int): Int = div(1, a)

    /** α^power for any (possibly negative) integer power. */
    fun alphaPow(power: Int): Int = exp[power.mod(ORDER)]

    /** Evaluates a polynomial stored lowest degree first at [x] using Horner's rule. */
    fun evalLowFirst(
        poly: IntArray,
        x: Int,
    ): Int {
        var y = 0
        for (i in poly.indices.reversed()) y = mul(y, x) xor poly[i]
        return y
    }

    /** Multiplies two polynomials stored lowest degree first. */
    fun mulPoly(
        p: IntArray,
        q: IntArray,
    ): IntArray {
        val r = IntArray(p.size + q.size - 1)
        for (i in p.indices) {
            if (p[i] == 0) continue
            for (j in q.indices) r[i + j] = r[i + j] xor mul(p[i], q[j])
        }
        return r
    }
}
