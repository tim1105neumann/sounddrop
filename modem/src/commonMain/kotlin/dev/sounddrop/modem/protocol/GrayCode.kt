package dev.sounddrop.modem.protocol

/**
 * Gray-code mapping between nibble values and tone indices, so that mistaking a tone for its
 * neighbour only flips a single bit.
 */
public object GrayCode {
    /** Tone index that carries [value]: the index whose Gray code equals [value]. */
    public fun toneFor(value: Int): Int {
        var tone = value
        var shift = value shr 1
        while (shift != 0) {
            tone = tone xor shift
            shift = shift shr 1
        }
        return tone
    }

    /** Value carried by tone [index]. */
    public fun valueOf(index: Int): Int = index xor (index shr 1)
}
