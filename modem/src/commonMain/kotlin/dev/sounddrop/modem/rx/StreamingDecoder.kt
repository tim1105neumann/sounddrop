package dev.sounddrop.modem.rx

import dev.sounddrop.modem.fec.Crc32
import dev.sounddrop.modem.fec.ReedSolomon
import dev.sounddrop.modem.protocol.FrameHeader
import dev.sounddrop.modem.protocol.FrameLayout
import dev.sounddrop.modem.protocol.ModemProfile
import dev.sounddrop.modem.rx.DecoderEvent.FailureReason
import dev.sounddrop.modem.rx.DecoderEvent.Section

/**
 * Turns a continuous stream of microphone samples into decoded frames.
 *
 * Feed audio in chunks of any size with [feed]; it returns everything that happened while
 * processing that chunk. The decoder is a small state machine:
 * searching for a preamble → reading the header → reading the payload → searching again.
 *
 * Not thread-safe: call it from one thread or coroutine at a time.
 */
public class StreamingDecoder(
    public val sampleRate: Int,
) {
    private val layout = FrameLayout(sampleRate)
    private val detector = PreambleDetector(sampleRate)
    private val demodulator = SymbolDemodulator(sampleRate)
    private val buffer = SampleBuffer()
    private var state: State = State.Searching(0L)

    private sealed interface State {
        data class Searching(
            val from: Long,
        ) : State

        class Header(
            val frameStart: Long,
            val readings: MutableList<SymbolReading> = mutableListOf(),
        ) : State

        class Payload(
            val frameStart: Long,
            val header: FrameHeader,
            val readings: MutableList<SymbolReading> = mutableListOf(),
        ) : State
    }

    /** Appends [samples] and returns the events they produced, in order. */
    public fun feed(samples: FloatArray): List<DecoderEvent> {
        buffer.append(samples)
        val events = mutableListOf<DecoderEvent>()
        while (step(events)) {
            // Keep advancing while there is enough audio for the next step.
        }
        buffer.discardBefore(oldestNeeded())
        return events
    }

    /** Drops all buffered audio and starts searching for a new preamble. */
    public fun reset() {
        buffer.discardBefore(buffer.end)
        state = State.Searching(buffer.end)
    }

    private fun step(events: MutableList<DecoderEvent>): Boolean =
        when (val s = state) {
            is State.Searching -> search(s, events)
            is State.Header -> readHeader(s, events)
            is State.Payload -> readPayload(s, events)
        }

    private fun search(
        s: State.Searching,
        events: MutableList<DecoderEvent>,
    ): Boolean =
        when (val scan = detector.scan(buffer, s.from)) {
            is PreambleDetector.Scan.Found -> {
                events += DecoderEvent.SyncFound(scan.start, scan.score)
                state = State.Header(scan.start)
                true
            }

            is PreambleDetector.Scan.NotYet -> {
                state = State.Searching(scan.nextFrom)
                false
            }
        }

    private fun readHeader(
        s: State.Header,
        events: MutableList<DecoderEvent>,
    ): Boolean {
        val profile = FrameLayout.HEADER_PROFILE
        val reading = readSymbol(s.frameStart + layout.headerStart, s.readings.size, profile) ?: return false
        s.readings += reading
        events += DecoderEvent.SymbolDecoded(Section.Header, s.readings.lastIndex, FrameHeader.SIZE, reading)
        if (s.readings.size < FrameHeader.SIZE) return true

        val header =
            FrameHeader.decode(s.readings.toBytes(), s.readings.erasures())
                ?: FrameHeader.decode(s.readings.toBytes())
        if (header == null) {
            events += DecoderEvent.FrameFailed(FailureReason.InvalidHeader)
            // Skip past this false peak, but not so far that a real preamble right after is missed.
            state = State.Searching(s.frameStart + layout.preambleSamples / 2)
        } else {
            events += DecoderEvent.HeaderDecoded(header)
            state = State.Payload(s.frameStart, header)
        }
        return true
    }

    private fun readPayload(
        s: State.Payload,
        events: MutableList<DecoderEvent>,
    ): Boolean {
        val profile = s.header.profile
        val count = FrameLayout.payloadSymbols(s.header.length)
        val reading = readSymbol(s.frameStart + layout.payloadStart, s.readings.size, profile) ?: return false
        s.readings += reading
        events += DecoderEvent.SymbolDecoded(Section.Payload, s.readings.lastIndex, count, reading)
        if (s.readings.size < count) return true

        events += finishFrame(s.header, s.readings)
        state = State.Searching(s.frameStart + layout.frameSamples(profile, s.header.length))
        return true
    }

    private fun readSymbol(
        sectionStart: Long,
        index: Int,
        profile: ModemProfile,
    ): SymbolReading? {
        val start = sectionStart + layout.symbolOffset(index, profile)
        val length = layout.symbolOffset(index + 1, profile) - layout.symbolOffset(index, profile)
        if (!buffer.contains(start, length)) return null
        return demodulator.demodulate(buffer.data, buffer.offsetOf(start), profile)
    }

    private fun finishFrame(
        header: FrameHeader,
        readings: List<SymbolReading>,
    ): DecoderEvent {
        val rs = ReedSolomon(FrameHeader.payloadParity(header.length))
        val received = readings.toBytes()
        // Mark the least confident symbols as erasures, but never more than the code can absorb.
        val erasures =
            readings.indices
                .filter { readings[it].erased }
                .sortedBy { readings[it].confidence }
                .take(rs.paritySize)
        val result =
            rs.decode(received, erasures)
                ?: rs.decode(received)
                ?: return DecoderEvent.FrameFailed(FailureReason.TooManyErrors)

        val dataEnd = header.length
        val fixed = result.codeword
        val crc =
            (0 until FrameHeader.CRC_SIZE).fold(0) { acc, i ->
                (acc shl 8) or (fixed[dataEnd + i].toInt() and 0xFF)
            }
        if (crc != Crc32.compute(fixed, 0, dataEnd)) return DecoderEvent.FrameFailed(FailureReason.ChecksumMismatch)

        return DecoderEvent.FrameDecoded(
            DecodedFrame(
                type = header.type,
                payload = fixed.copyOf(dataEnd),
                profile = header.profile,
                correctedSymbols = result.correctedPositions,
                erasedSymbols = erasures.sorted(),
                symbolCount = readings.size,
            ),
        )
    }

    private fun oldestNeeded(): Long =
        when (val s = state) {
            is State.Searching -> s.from
            is State.Header -> s.frameStart
            is State.Payload -> s.frameStart
        }

    private fun List<SymbolReading>.toBytes(): ByteArray = ByteArray(size) { this[it].value.toByte() }

    private fun List<SymbolReading>.erasures(): List<Int> = indices.filter { this[it].erased }
}
