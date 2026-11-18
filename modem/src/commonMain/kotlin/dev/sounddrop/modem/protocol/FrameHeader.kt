package dev.sounddrop.modem.protocol

import dev.sounddrop.modem.fec.ReedSolomon

/**
 * The fixed-size header that follows the preamble. It is always sent with [ModemProfile.Robust]
 * timing, so a receiver can read it before it knows which profile the payload uses.
 *
 * On air: `[version:4 | profile:4] [type] [length]` plus [PARITY_SIZE] Reed–Solomon bytes.
 */
public data class FrameHeader(
    val profile: ModemProfile,
    val type: Int,
    val length: Int,
) {
    init {
        require(type in 0..255) { "type must fit in one byte" }
        require(length in 0..MAX_PAYLOAD) { "payload length must be in 0..$MAX_PAYLOAD" }
    }

    /** Header bytes including parity, ready to be modulated. */
    public fun encode(): ByteArray =
        rs.encode(
            byteArrayOf(((VERSION shl 4) or profile.id).toByte(), type.toByte(), length.toByte()),
        )

    public companion object {
        /** Protocol version carried in every header. */
        public const val VERSION: Int = 1

        /** Largest payload a single frame can carry. */
        public const val MAX_PAYLOAD: Int = 160

        /** Reed–Solomon parity bytes protecting the header. */
        public const val PARITY_SIZE: Int = 4

        /** Bytes of CRC-32 appended to the payload before error correction. */
        public const val CRC_SIZE: Int = 4

        /** Header length on air, in symbols (= bytes). */
        public const val SIZE: Int = 3 + PARITY_SIZE

        private val rs = ReedSolomon(PARITY_SIZE)

        /**
         * Decodes a received header, correcting up to two damaged bytes. Returns `null` if the
         * bytes are not a valid header, which usually means the preamble was a false detection.
         */
        public fun decode(
            bytes: ByteArray,
            erasures: Collection<Int> = emptyList(),
        ): FrameHeader? {
            if (bytes.size != SIZE) return null
            val fixed = rs.decode(bytes, erasures)?.codeword ?: return null
            val first = fixed[0].toInt() and 0xFF
            if (first shr 4 != VERSION) return null
            val profile = ModemProfile.fromId(first and 0x0F) ?: return null
            val length = fixed[2].toInt() and 0xFF
            if (length > MAX_PAYLOAD) return null
            return FrameHeader(profile, fixed[1].toInt() and 0xFF, length)
        }

        /**
         * Parity bytes used for a payload of [length] bytes: the payload plus its CRC is protected
         * by roughly 50 % redundancy, so up to a quarter of the symbols may be lost.
         */
        public fun payloadParity(length: Int): Int = maxOf(8, 2 * ((length + CRC_SIZE + 3) / 4))
    }
}
