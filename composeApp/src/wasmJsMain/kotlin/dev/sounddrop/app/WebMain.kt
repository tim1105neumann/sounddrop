package dev.sounddrop.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import dev.sounddrop.app.audio.WebAudioEngine
import kotlinx.browser.document

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val engine = WebAudioEngine()
    ComposeViewport(document.body!!) {
        App(engine)
    }
}
