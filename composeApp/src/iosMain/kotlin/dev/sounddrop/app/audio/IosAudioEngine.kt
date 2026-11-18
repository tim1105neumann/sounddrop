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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioEngineConfigurationChangeNotification
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPlayerNode
import platform.AVFAudio.AVAudioPlayerNodeCompletionDataPlayedBack
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionAllowBluetoothA2DP
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionModeMeasurement
import platform.AVFAudio.AVAudioSessionRecordPermissionDenied
import platform.AVFAudio.AVAudioSessionRecordPermissionGranted
import platform.AVFAudio.setActive
import platform.Foundation.NSError
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.darwin.NSObjectProtocol
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds

/**
 * iOS audio through one shared [AVAudioEngine]: the microphone is tapped on its input node and
 * transmissions go through an [AVAudioPlayerNode] into the main mixer, so sending and listening can
 * run at the same time.
 *
 * The session uses the measurement mode, which turns off automatic gain control and voice
 * processing, and routes output to the loudspeaker instead of the earpiece.
 *
 * When the system stops the engine (an interruption such as a call or Siri, or a configuration
 * change such as plugging in headphones), a running [play] fails, a running [capture] closes with
 * [MicrophoneUnavailableException], and the session is set up again on the next use.
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
    private var pendingPlayback: CompletableDeferred<Unit>? = null
    private var activeCapture: SendChannel<AudioChunk>? = null
    private var observers: List<NSObjectProtocol> = emptyList()

    init {
        engine.attachNode(player)
    }

    override suspend fun play(
        samples: FloatArray,
        sampleRate: Int,
    ) {
        if (samples.isEmpty()) return
        require(sampleRate > 0) { "Invalid sample rate: $sampleRate" }
        playLock.withLock {
            val format =
                AVAudioFormat(standardFormatWithSampleRate = sampleRate.toDouble(), channels = 1u)
            val buffer = AVAudioPCMBuffer(pCMFormat = format, frameCapacity = samples.size.toUInt())
            buffer.frameLength = samples.size.toUInt()
            val channel = buffer.floatChannelData?.get(0) ?: error("Could not allocate the audio buffer")
            for (i in samples.indices) channel[i] = samples[i]
            val finished = CompletableDeferred<Unit>()
            graphLock.withLock {
                observeSystemEvents()
                try {
                    configureSession()
                    if (playerRate != format.sampleRate) {
                        engine.disconnectNodeOutput(player)
                        engine.connect(player, engine.mainMixerNode, format)
                        playerRate = format.sampleRate
                    }
                    startEngine()
                } catch (e: IllegalStateException) {
                    stopEngineIfIdle()
                    throw e
                }
                transmitting = true
                pendingPlayback = finished
            }
            try {
                player.scheduleBuffer(buffer, AVAudioPlayerNodeCompletionDataPlayedBack) { _ ->
                    finished.complete(Unit)
                }
                if (!player.playing) player.play()
                // Safety net for an engine stop that no notification reports: play() must never
                // hang while holding its lock.
                val timeout = (samples.size.toDouble() / sampleRate).seconds + PLAYBACK_GRACE
                withTimeoutOrNull(timeout) { finished.await() }
                    ?: error("Playback did not finish, the audio output stopped")
            } finally {
                withContext(NonCancellable) {
                    graphLock.withLock {
                        player.stop()
                        transmitting = false
                        pendingPlayback = null
                        stopEngineIfIdle()
                    }
                }
            }
        }
    }

    override fun capture(): Flow<AudioChunk> =
        callbackFlow {
            if (!requestRecordPermission()) {
                throw MicrophoneUnavailableException("Microphone access was denied")
            }
            // Enabling the input stops the engine, which would cut off a running transmission, so
            // the first capture waits until that has finished.
            if (inputConfigured) startCapture() else playLock.withLock { startCapture() }
            try {
                awaitClose()
            } finally {
                withContext(NonCancellable) {
                    graphLock.withLock {
                        engine.inputNode.removeTapOnBus(0u)
                        capturing = false
                        if (activeCapture === channel) activeCapture = null
                        stopEngineIfIdle()
                    }
                }
            }
        }

    private suspend fun ProducerScope<AudioChunk>.startCapture() {
        graphLock.withLock {
            observeSystemEvents()
            try {
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
            } catch (e: MicrophoneUnavailableException) {
                stopEngineIfIdle()
                throw e
            }
            capturing = true
            activeCapture = channel
        }
    }

    /**
     * Fails whatever is running when the system has stopped the engine. Runs on the main queue
     * without the graph lock, so it only completes the waiters; their cleanup takes the lock.
     */
    private fun onEngineStopped(reason: String) {
        sessionConfigured = false
        playerRate = 0.0
        pendingPlayback?.completeExceptionally(IllegalStateException("Sending stopped: $reason"))
        activeCapture?.close(MicrophoneUnavailableException("Listening stopped: $reason"))
    }

    private fun observeSystemEvents() {
        if (observers.isNotEmpty()) return
        val center = NSNotificationCenter.defaultCenter
        val queue = NSOperationQueue.mainQueue
        observers =
            listOf(
                center.addObserverForName(
                    name = AVAudioEngineConfigurationChangeNotification,
                    `object` = engine,
                    queue = queue,
                ) { _ -> onEngineStopped("the audio device changed") },
                center.addObserverForName(
                    name = AVAudioSessionInterruptionNotification,
                    `object` = AVAudioSession.sharedInstance(),
                    queue = queue,
                ) { notification ->
                    if (notification.isInterruptionBegan()) {
                        onEngineStopped("audio was interrupted by a call or another app")
                    }
                },
            )
    }

    private fun NSNotification?.isInterruptionBegan(): Boolean {
        val type = this?.userInfo?.get(AVAudioSessionInterruptionTypeKey) as? NSNumber
        return type?.unsignedLongValue == AVAudioSessionInterruptionTypeBegan
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

    /**
     * Stops the engine once neither direction needs it, which also releases the microphone, and
     * stops listening for system events until the engine is used again.
     */
    private fun stopEngineIfIdle() {
        if (capturing || transmitting) return
        engine.stop()
        val center = NSNotificationCenter.defaultCenter
        observers.forEach { center.removeObserver(it) }
        observers = emptyList()
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
                error("$message: ${error.value?.localizedDescription ?: "unknown error"}")
            }
        }
    }

    private companion object {
        const val TAP_FRAMES = 4096u
        val PLAYBACK_GRACE = 5.seconds
    }
}
