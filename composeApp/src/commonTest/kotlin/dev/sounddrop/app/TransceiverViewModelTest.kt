package dev.sounddrop.app

import dev.sounddrop.app.audio.FakeAudioEngine
import dev.sounddrop.modem.channel.ChannelConditions
import dev.sounddrop.modem.channel.ChannelSimulator
import dev.sounddrop.modem.payload.Payload
import dev.sounddrop.modem.protocol.ModemProfile
import dev.sounddrop.modem.tx.Modulator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class TransceiverViewModelTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun listeningDecodesALinkFromTheMicrophone() =
        runTest {
            val link = Payload.Link("https://kotlinlang.org")
            val tx = Modulator(48_000).modulate(Payload.encode(link), Payload.typeOf(link), ModemProfile.Fast)
            val recording =
                ChannelSimulator(Random(3))
                    .transmit(tx.samples, 48_000, ChannelConditions(snrDb = 10.0, receiverSampleRate = 44_100))
            val viewModel = TransceiverViewModel(FakeAudioEngine(recording.samples, recording.sampleRate))

            viewModel.toggleListening()
            val inbox =
                withContext(Dispatchers.Default) {
                    withTimeout(30_000) { viewModel.state.first { it.inbox.isNotEmpty() }.inbox }
                }
            assertEquals(link, inbox.single().payload)
        }

    @Test
    fun oversizedMessagesAreRejectedWithoutPlaying() {
        val engine = FakeAudioEngine()
        val viewModel = TransceiverViewModel(engine)
        viewModel.send(Payload.Text("x".repeat(500)), ModemProfile.Robust)
        assertEquals(0, engine.played.size)
        assertEquals(
            true,
            viewModel.state.value.error
                ?.contains("limit"),
        )
    }
}
