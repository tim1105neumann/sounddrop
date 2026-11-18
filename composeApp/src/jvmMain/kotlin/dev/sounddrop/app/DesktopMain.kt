package dev.sounddrop.app

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.sounddrop.app.audio.JvmAudioEngine

fun main() {
    val engine = JvmAudioEngine()
    application {
        Window(onCloseRequest = ::exitApplication, title = "SoundDrop") {
            App(engine)
        }
    }
}
