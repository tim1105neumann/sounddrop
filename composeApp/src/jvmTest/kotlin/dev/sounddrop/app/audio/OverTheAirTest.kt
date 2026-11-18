package dev.sounddrop.app.audio

import dev.sounddrop.modem.protocol.ModemProfile
import dev.sounddrop.modem.rx.DecoderEvent
import dev.sounddrop.modem.rx.StreamingDecoder
import dev.sounddrop.modem.tx.Modulator
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Plays a frame through the real speaker and decodes it from the real microphone.
 * Only runs when `SOUNDDROP_AIR_TEST=1` is set, because it makes noise and needs a microphone.
 */
class OverTheAirTest {
    @Test
    fun speakerToMicrophone() {
        if (System.getenv("SOUNDDROP_AIR_TEST") != "1") return
        val engine = JvmAudioEngine()
        val message = "Over the air!"
        runBlocking {
            val received =
                async {
                    var decoded: String? = null
                    var decoder: StreamingDecoder? = null
                    withTimeout(15_000) {
                        engine.capture().takeWhile { decoded == null }.collect { chunk ->
                            val d = decoder ?: StreamingDecoder(chunk.sampleRate).also { decoder = it }
                            for (event in d.feed(chunk.samples)) {
                                println("event: $event")
                                if (event is DecoderEvent.FrameDecoded) decoded = event.frame.payload.decodeToString()
                            }
                        }
                    }
                    decoded
                }
            delay(500)
            val tx = Modulator(48_000).modulate(message.encodeToByteArray(), type = 0, profile = ModemProfile.Robust)
            engine.play(tx.samples, 48_000)
            assertEquals(message, received.await())
        }
    }
}
