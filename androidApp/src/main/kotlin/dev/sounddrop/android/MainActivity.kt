package dev.sounddrop.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import dev.sounddrop.app.App
import dev.sounddrop.app.audio.AndroidAudioEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Hosts the shared Compose UI and provides the Android audio engine. */
class MainActivity : ComponentActivity() {
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            MicrophonePermission.onResult(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        MicrophonePermission.launcher = permissionLauncher
        setContent { App(engine(applicationContext)) }
    }

    override fun onDestroy() {
        if (MicrophonePermission.launcher === permissionLauncher) MicrophonePermission.launcher = null
        super.onDestroy()
    }

    private companion object {
        private var engine: AndroidAudioEngine? = null

        /**
         * One engine per process: the view model that uses it survives activity recreation, so it
         * must not hold on to a particular activity.
         */
        fun engine(context: Context): AndroidAudioEngine =
            engine ?: AndroidAudioEngine(context) { MicrophonePermission.request(context) }.also { engine = it }
    }
}

/** Bridges the suspend-style permission request of the engine to the current activity's launcher. */
private object MicrophonePermission {
    var launcher: ActivityResultLauncher<String>? = null
    private var pending: CompletableDeferred<Boolean>? = null

    suspend fun request(context: Context): Boolean {
        val permission = Manifest.permission.RECORD_AUDIO
        if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) return true
        val result = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {
            pending?.complete(false)
            pending = result
            launcher?.launch(permission) ?: result.complete(false)
        }
        return result.await()
    }

    fun onResult(granted: Boolean) {
        pending?.complete(granted)
        pending = null
    }
}
