package dev.sounddrop.app

import androidx.compose.ui.window.ComposeUIViewController
import dev.sounddrop.app.audio.IosAudioEngine

/** Entry point for the iOS app: hosts the shared [App] in a `UIViewController`. */
@Suppress("ktlint:standard:function-naming", "FunctionName")
fun MainViewController() = ComposeUIViewController { App(IosAudioEngine()) }
