package dev.sounddrop.app.audio

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPlayerNode
import platform.AVFAudio.AVAudioPlayerNodeCompletionDataPlayedBack
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionAllowBluetoothA2DP
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionModeMeasurement
import platform.AVFAudio.AVAudioSessionRecordPermissionDenied
import platform.AVFAudio.AVAudioSessionRecordPermissionGranted
import platform.AVFAudio.setActive
import platform.Foundation.NSError
import kotlin.coroutines.resume

/**
 * iOS audio through one shared [AVAudioEngine]: the microphone is tapped on its input node and
 * transmissions go through an [AVAudioPlayerNode] into the main mixer, so sending and listening can
 * run at the same time.
 *
 * The session uses the measurement mode, which turns off automatic gain control and voice
 * processing, and routes output to the loudspeaker instead of the earpiece.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosAudioEngine : AudioEngine {
    private val engine = AVAudioEngine()
    private val player = AVAudioPlayerNode()
    private val graphLock = Mutex()
    private val playLock = Mutex()
    private var sessionConfigured = false
    private var inputConfigured = false
    private var capturing = false
    private var transmitting = false
    private var playerRate = 0.0

    init {
        engine.attachNode(player)
    }

    override suspend fun play(
        samples: FloatArray,
        sampleRate: Int,
    ) {
        if (samples.isEmpty()) return
        playLock.withLock {
            val format =
                AVAudioFormat(standardFormatWithSampleRate = sampleRate.toDouble(), channels = 1u)
            graphLock.withLock {
                configureSession()
                if (playerRate != format.sampleRate) {
                    engine.disconnectNodeOutput(player)
                    engine.connect(player, engine.mainMixerNode, format)
                    playerRate = format.sampleRate
                }
                startEngine()
                transmitting = true
            }
            val buffer = AVAudioPCMBuffer(pCMFormat = format, frameCapacity = samples.size.toUInt())
            buffer.frameLength = samples.size.toUInt()
            val channel = buffer.floatChannelData!![0]!!
            for (i in samples.indices) channel[i] = samples[i]
            try {
                suspendCancellableCoroutine { continuation ->
                    player.scheduleBuffer(buffer, AVAudioPlayerNodeCompletionDataPlayedBack) { _ ->
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                    if (!player.playing) player.play()
                }
            } finally {
                withContext(NonCancellable) {
                    graphLock.withLock {
                        player.stop()
                        transmitting = false
                        stopEngineIfIdle()
                    }
                }
            }
        }
    }

    override fun capture(): Flow<AudioChunk> =
        callbackFlow {
            graphLock.withLock {
                if (!requestRecordPermission()) {
                    throw MicrophoneUnavailableException("Microphone access was denied")
                }
                try {
                    configureSession()
                } catch (e: IllegalStateException) {
                    throw MicrophoneUnavailableException("Audio session unavailable", e)
                }
                if (!inputConfigured && engine.running) {
                    // The input node can only be enabled while the engine is stopped.
                    engine.stop()
                }
                val input = engine.inputNode
                inputConfigured = true
                val format = input.outputFormatForBus(0u)
                if (format.sampleRate <= 0.0 || format.channelCount == 0u) {
                    throw MicrophoneUnavailableException("No usable microphone found")
                }
                val rate = format.sampleRate.toInt()
                input.installTapOnBus(0u, TAP_FRAMES, format) { buffer, _ ->
                    val data = buffer?.floatChannelData?.get(0) ?: return@installTapOnBus
                    val samples = FloatArray(buffer.frameLength.toInt()) { data[it] }
                    trySend(AudioChunk(samples, rate))
                }
                try {
                    startEngine()
                } catch (e: IllegalStateException) {
                    input.removeTapOnBus(0u)
                    throw MicrophoneUnavailableException("Could not start recording", e)
                }
                capturing = true
            }
            try {
                awaitClose()
            } finally {
                withContext(NonCancellable) {
                    graphLock.withLock {
                        engine.inputNode.removeTapOnBus(0u)
                        capturing = false
                        stopEngineIfIdle()
                    }
                }
            }
        }

    private fun configureSession() {
        if (sessionConfigured) return
        val session = AVAudioSession.sharedInstance()
        // Without DefaultToSpeaker, playAndRecord sends audio to the quiet earpiece.
        val options = AVAudioSessionCategoryOptionDefaultToSpeaker or AVAudioSessionCategoryOptionAllowBluetoothA2DP
        withNSError("Could not configure the audio session") { error ->
            session.setCategory(
                AVAudioSessionCategoryPlayAndRecord,
                mode = AVAudioSessionModeMeasurement,
                options = options,
                error = error,
            )
        }
        withNSError("Could not activate the audio session") { error -> session.setActive(true, error) }
        sessionConfigured = true
    }

    /** Stops the engine once neither direction needs it, which also releases the microphone. */
    private fun stopEngineIfIdle() {
        if (!capturing && !transmitting) engine.stop()
    }

    private fun startEngine() {
        if (engine.running) return
        engine.prepare()
        withNSError("Could not start the audio engine") { error -> engine.startAndReturnError(error) }
    }

    @Suppress("DEPRECATION")
    private suspend fun requestRecordPermission(): Boolean {
        val session = AVAudioSession.sharedInstance()
        return when (session.recordPermission) {
            AVAudioSessionRecordPermissionGranted -> true
            AVAudioSessionRecordPermissionDenied -> false
            else ->
                suspendCancellableCoroutine { continuation ->
                    session.requestRecordPermission { granted -> continuation.resume(granted) }
                }
        }
    }

    private inline fun withNSError(
        message: String,
        block: (CPointer<ObjCObjectVar<NSError?>>) -> Boolean,
    ) {
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            if (!block(error.ptr)) {
                error("$message: ${error.value?.localizedDescription}")
            }
        }
    }

    private companion object {
        const val TAP_FRAMES = 4096u
    }
}
