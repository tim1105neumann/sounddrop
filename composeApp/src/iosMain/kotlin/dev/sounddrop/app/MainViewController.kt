package dev.sounddrop.app

import androidx.compose.ui.window.ComposeUIViewController
import dev.sounddrop.app.audio.IosAudioEngine
import platform.UIKit.UIViewController

/** Entry point for the iOS app: hosts the shared [App] in a `UIViewController`. */
@Suppress("ktlint:standard:function-naming", "FunctionName")
fun MainViewController(): UIViewController {
    // Created once here: inside the composable lambda it would be recreated on recomposition.
    val engine = IosAudioEngine()
    return ComposeUIViewController { App(engine) }
}
