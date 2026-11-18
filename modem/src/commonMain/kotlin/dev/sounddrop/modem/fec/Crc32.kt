package dev.sounddrop.modem.fec

/** CRC-32 (IEEE 802.3, reflected polynomial 0xEDB88320), as used by zip and PNG. */
public object Crc32 {
    private val table =
        IntArray(256) { n ->
            var c = n
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
            c
        }

    /** Computes the checksum of [bytes] in `[from, to)`. */
    public fun compute(
        bytes: ByteArray,
        from: Int = 0,
        to: Int = bytes.size,
    ): Int {
        var crc = -1
        for (i in from until to) crc = table[(crc xor bytes[i].toInt()) and 0xFF] xor (crc ushr 8)
        return crc.inv()
    }
}
