package dev.sounddrop.modem.rx

import dev.sounddrop.modem.channel.ChannelConditions
import dev.sounddrop.modem.channel.ChannelSimulator
import dev.sounddrop.modem.protocol.ModemProfile
import dev.sounddrop.modem.tx.Modulator
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class EndToEndTest {
    private val random = Random(7)
    private val message = "https://kotlinconf.com — sent over sound!".encodeToByteArray()

    @Test
    fun cleanChannelRobust() {
        assertRoundTrip(ModemProfile.Robust, ChannelConditions())
    }

    @Test
    fun cleanChannelFast() {
        assertRoundTrip(ModemProfile.Fast, ChannelConditions())
    }

    @Test
    fun noisyChannelRobust() {
        assertRoundTrip(ModemProfile.Robust, ChannelConditions(snrDb = 0.0))
    }

    @Test
    fun noisyChannelFast() {
        assertRoundTrip(ModemProfile.Fast, ChannelConditions(snrDb = 6.0))
    }

    @Test
    fun roomEcho() {
        assertRoundTrip(ModemProfile.Robust, ChannelConditions(snrDb = 15.0, echoDelaySeconds = 0.012, echoGain = 0.6))
    }

    @Test
    fun senderAt44kReceiverAt48k() {
        assertRoundTrip(ModemProfile.Robust, ChannelConditions(receiverSampleRate = 48_000), senderRate = 44_100)
    }

    @Test
    fun senderAt48kReceiverAt44k() {
        assertRoundTrip(ModemProfile.Fast, ChannelConditions(receiverSampleRate = 44_100, clockPpm = -150.0))
    }

    @Test
    fun clockDrift() {
        assertRoundTrip(ModemProfile.Robust, ChannelConditions(clockPpm = 300.0))
    }

    @Test
    fun noiseBurstIsRepaired() {
        val frame =
            assertRoundTrip(
                ModemProfile.Robust,
                ChannelConditions(snrDb = 20.0, burstAtSeconds = 1.5, burstSeconds = 0.4, burstLevel = 30.0),
            )
        assertTrue(frame.correctedSymbols.isNotEmpty(), "the burst should have damaged some symbols")
    }

    @Test
    fun maximumPayload() {
        val payload = random.nextBytes(160)
        val frame = decodeAll(transmit(payload, ModemProfile.Fast, ChannelConditions(snrDb = 12.0))).single()
        assertContentEquals(payload, frame.payload)
    }

    @Test
    fun emptyPayload() {
        val frame = decodeAll(transmit(ByteArray(0), ModemProfile.Robust, ChannelConditions())).single()
        assertEquals(0, frame.payload.size)
    }

    @Test
    fun chunkSizeDoesNotMatter() {
        val audio = transmit(message, ModemProfile.Robust, ChannelConditions(snrDb = 10.0))
        for (chunk in listOf(1, 127, 4096, audio.samples.size)) {
            val frame = decodeAll(audio, chunkSize = chunk).single()
            assertContentEquals(message, frame.payload, "chunk=$chunk")
        }
    }

    @Test
    fun backToBackFrames() {
        val first = transmit("first".encodeToByteArray(), ModemProfile.Robust, ChannelConditions())
        val second = transmit("second".encodeToByteArray(), ModemProfile.Fast, ChannelConditions())
        val frames = decodeAll(ChannelSimulator.Output(first.samples + second.samples, first.sampleRate))
        assertEquals(listOf("first", "second"), frames.map { it.payload.decodeToString() })
    }

    @Test
    fun noiseAloneProducesNoFrames() {
        val noise = FloatArray(48_000 * 10) { (random.nextDouble(-0.3, 0.3)).toFloat() }
        val events = StreamingDecoder(48_000).feed(noise)
        assertTrue(events.none { it is DecoderEvent.FrameDecoded })
        assertTrue(events.none { it is DecoderEvent.SyncFound }, "false sync: $events")
    }

    private fun assertRoundTrip(
        profile: ModemProfile,
        conditions: ChannelConditions,
        senderRate: Int = 48_000,
    ): DecodedFrame {
        val frame =
            decodeAll(transmit(message, profile, conditions, senderRate)).singleOrNull()
                ?: fail("no frame decoded for $profile under $conditions")
        assertContentEquals(message, frame.payload)
        assertEquals(profile, frame.profile)
        assertEquals(3, frame.type)
        return frame
    }

    private fun transmit(
        payload: ByteArray,
        profile: ModemProfile,
        conditions: ChannelConditions,
        senderRate: Int = 48_000,
    ): ChannelSimulator.Output {
        val tx = Modulator(senderRate).modulate(payload, type = 3, profile = profile)
        return ChannelSimulator(random).transmit(tx.samples, senderRate, conditions)
    }

    private fun decodeAll(
        audio: ChannelSimulator.Output,
        chunkSize: Int = 2048,
    ): List<DecodedFrame> {
        val decoder = StreamingDecoder(audio.sampleRate)
        val events =
            audio.samples
                .asList()
                .chunked(chunkSize)
                .flatMap { decoder.feed(it.toFloatArray()) } +
                decoder.feed(FloatArray(audio.sampleRate))
        return events.filterIsInstance<DecoderEvent.FrameDecoded>().map { it.frame }
    }
}
