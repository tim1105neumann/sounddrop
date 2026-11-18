package dev.sounddrop.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import dev.sounddrop.app.App
import dev.sounddrop.app.ReceivedMessage
import dev.sounddrop.app.Reception
import dev.sounddrop.app.TransceiverState
import dev.sounddrop.app.audio.FakeAudioEngine
import dev.sounddrop.app.lab.LabScreen
import dev.sounddrop.app.lab.LabViewModel
import dev.sounddrop.modem.payload.Payload
import dev.sounddrop.modem.payload.SketchCodec
import dev.sounddrop.modem.protocol.ModemProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.Test

/**
 * Renders screens to PNG files for the README and for visual checks. Only runs when
 * `SOUNDDROP_SCREENSHOTS` points at an output directory.
 */
class ScreenshotTest {
    private val outDir = System.getenv("SOUNDDROP_SCREENSHOTS")?.let(::File)

    @Test
    fun renderScreens() {
        val dir = outDir ?: return
        dir.mkdirs()
        render(dir, "send-wide", 1100, 760) { App(FakeAudioEngine()) }
        render(dir, "send-phone", 412, 880) { App(FakeAudioEngine()) }
        val heart =
            (0..200).map { i ->
                val t = i / 200.0 * 2 * PI
                val x = 16 * sin(t).pow(3)
                val y = 13 * cos(t) - 5 * cos(2 * t) - 2 * cos(3 * t) - cos(4 * t)
                (0.5 + x / 40).toFloat() to (0.45 - y / 40).toFloat()
            }
        val inbox =
            listOf(
                ReceivedMessage(0, Payload.Sketch(SketchCodec.fit(listOf(heart), 159)), ModemProfile.Robust, 3, 120),
                ReceivedMessage(1, Payload.Link("https://kotlinconf.com"), ModemProfile.Fast, 0, 46),
            )
        render(dir, "listen-phone", 412, 1100) {
            SoundDropTheme {
                Surface {
                    ListenScreen(
                        TransceiverState(listening = true, reception = Reception.Searching, inbox = inbox),
                        flowOf(),
                        onToggleListening = {},
                        onClearInbox = {},
                    )
                }
            }
        }
        val lab = LabViewModel()
        lab.update { it.copy(snrDb = 2f, echoGain = 0.5f) }
        lab.run()
        runBlocking { withTimeout(30_000) { lab.state.first { it.result != null } } }
        render(dir, "lab-wide", 1100, 1500) { SoundDropTheme { Surface { LabScreen(viewModel = lab) } } }
    }

    private fun render(
        dir: File,
        name: String,
        width: Int,
        height: Int,
        content: @Composable () -> Unit,
    ) {
        val owner =
            object : ViewModelStoreOwner {
                override val viewModelStore = ViewModelStore()
            }
        ImageComposeScene(width * 2, height * 2, Density(2f)) {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                SoundDropTheme { Surface(Modifier.fillMaxSize()) { content() } }
            }
        }.use { scene ->
            // Step the frame clock so animations (such as the sketch replay) can finish.
            for (frame in 0 until 60) scene.render(frame * 50_000_000L)
            val image = scene.render(3_000_000_000L)
            File(dir, "$name.png").writeBytes(image.encodeToData()!!.bytes)
        }
    }
}
