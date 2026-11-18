package dev.sounddrop.app.ui

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import dev.sounddrop.app.App
import dev.sounddrop.app.TransceiverState
import dev.sounddrop.app.audio.FakeAudioEngine
import dev.sounddrop.app.lab.LabScreen
import dev.sounddrop.app.lab.LabViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
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
        render(dir, "listen-phone", 412, 880) {
            SoundDropTheme {
                Surface { ListenScreen(TransceiverState(), flowOf(), onToggleListening = {}, onClearInbox = {}) }
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
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { content() }
        }.use { scene ->
            scene.render(0)
            val image = scene.render(500_000_000)
            File(dir, "$name.png").writeBytes(image.encodeToData()!!.bytes)
        }
    }
}
