package dev.sounddrop.modem.rx

import dev.sounddrop.modem.protocol.FrameHeader
import dev.sounddrop.modem.protocol.ModemProfile

/** Progress reported by [StreamingDecoder] while it listens. */
public sealed interface DecoderEvent {
    /** A preamble was detected at absolute sample [sampleIndex] with correlation [score] (0..1). */
    public data class SyncFound(
        val sampleIndex: Long,
        val score: Float,
    ) : DecoderEvent

    /** Symbol [index] of [count] in [section] was demodulated. */
    public data class SymbolDecoded(
        val section: Section,
        val index: Int,
        val count: Int,
        val reading: SymbolReading,
    ) : DecoderEvent

    /** The header was read; the payload of [FrameHeader.length] bytes follows. */
    public data class HeaderDecoded(
        val header: FrameHeader,
    ) : DecoderEvent

    /** A complete frame passed error correction and its CRC. */
    public data class FrameDecoded(
        val frame: DecodedFrame,
    ) : DecoderEvent

    /** A frame was detected but could not be recovered. */
    public data class FrameFailed(
        val reason: FailureReason,
    ) : DecoderEvent

    /** Part of a frame a symbol belongs to. */
    public enum class Section { Header, Payload }

    /** Why a frame could not be decoded. */
    public enum class FailureReason {
        /** The header was unreadable; usually the "preamble" was just noise. */
        InvalidHeader,

        /** Too many symbols were damaged for Reed–Solomon to repair. */
        TooManyErrors,

        /** Error correction produced data that fails the CRC-32 check. */
        ChecksumMismatch,
    }
}

/**
 * A received frame.
 *
 * @property type application-level payload type from the header.
 * @property payload the payload bytes.
 * @property profile timing profile the sender used.
 * @property correctedSymbols indices (into payload + CRC + parity) that error correction repaired.
 * @property erasedSymbols indices the demodulator flagged as uncertain.
 * @property symbolCount total payload symbols on air.
 */
public class DecodedFrame(
    public val type: Int,
    public val payload: ByteArray,
    public val profile: ModemProfile,
    public val correctedSymbols: List<Int>,
    public val erasedSymbols: List<Int>,
    public val symbolCount: Int,
)
