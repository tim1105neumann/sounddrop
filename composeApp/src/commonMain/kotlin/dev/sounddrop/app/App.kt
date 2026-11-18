package dev.sounddrop.app

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.sounddrop.app.audio.AudioEngine
import dev.sounddrop.app.lab.LabScreen
import dev.sounddrop.app.ui.AboutScreen
import dev.sounddrop.app.ui.ListenScreen
import dev.sounddrop.app.ui.NavIcon
import dev.sounddrop.app.ui.SendScreen
import dev.sounddrop.app.ui.SoundDropTheme

/** Top-level destinations. */
enum class Screen(
    val label: String,
) {
    Send("Send"),
    Listen("Listen"),
    Lab("Lab"),
    About("About"),
}

/** Root of the shared UI; each platform only supplies its [AudioEngine]. */
@Composable
fun App(engine: AudioEngine) {
    val viewModel = viewModel { TransceiverViewModel(engine) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(Screen.Send) }

    SoundDropTheme {
        Surface(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.safeDrawingPadding()) {
                val wide = maxWidth >= 720.dp
                val content: @Composable (Modifier) -> Unit = { modifier ->
                    when (screen) {
                        Screen.Send -> SendScreen(state, viewModel::send, modifier)
                        Screen.Listen ->
                            ListenScreen(
                                state,
                                viewModel.inputLevel,
                                viewModel.spectrum,
                                viewModel::toggleListening,
                                viewModel::clearInbox,
                                modifier,
                            )
                        Screen.Lab -> LabScreen(modifier)
                        Screen.About -> AboutScreen(modifier)
                    }
                }
                if (wide) {
                    Row {
                        NavigationRail {
                            Screen.entries.forEach { entry ->
                                NavigationRailItem(
                                    selected = screen == entry,
                                    onClick = { screen = entry },
                                    icon = { NavIcon(entry) },
                                    label = { Text(entry.label) },
                                )
                            }
                        }
                        content(Modifier.widthIn(max = 900.dp))
                    }
                } else {
                    Scaffold(
                        bottomBar = {
                            NavigationBar {
                                Screen.entries.forEach { entry ->
                                    NavigationBarItem(
                                        selected = screen == entry,
                                        onClick = { screen = entry },
                                        icon = { NavIcon(entry) },
                                        label = { Text(entry.label) },
                                    )
                                }
                            }
                        },
                    ) { padding ->
                        content(Modifier.fillMaxSize().padding(padding))
                    }
                }
            }
        }
    }
}
