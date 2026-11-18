package dev.sounddrop.app.audio

import kotlinx.coroutines.await
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.khronos.webgl.Float32Array
import org.khronos.webgl.toFloat32Array
import org.khronos.webgl.toFloatArray
import org.w3c.dom.MessageEvent
import org.w3c.dom.MessagePort
import org.w3c.dom.events.Event
import org.w3c.dom.mediacapture.MediaStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.js.Promise

/**
 * Browser audio through the Web Audio API. One [AudioContext] is shared by playback and capture
 * and created on the first user gesture, because browsers refuse to start audio without one.
 * Capture runs in an AudioWorklet and falls back to the deprecated ScriptProcessorNode where
 * worklets are unavailable.
 */
class WebAudioEngine : AudioEngine {
    private var context: AudioContext? = null
    private var workletLoaded: Promise<JsAny?>? = null

    init {
        // Unlock audio inside a real gesture handler; coroutines started from a click may run too
        // late for strict autoplay policies (Safari).
        unlockOnGesture { context() }
    }

    override suspend fun play(
        samples: FloatArray,
        sampleRate: Int,
    ) {
        if (samples.isEmpty()) return
        val context = runningContext()
        val buffer = context.createBuffer(1, samples.size, sampleRate.toDouble())
        buffer.copyToChannel(samples.toFloat32Array(), 0)
        val source = context.createBufferSource()
        source.buffer = buffer
        source.connect(context.destination)
        try {
            suspendCancellableCoroutine { continuation ->
                source.onended = { continuation.resume(Unit) }
                continuation.invokeOnCancellation { source.stop() }
                source.start()
            }
        } finally {
            source.disconnect()
        }
    }

    override fun capture(): Flow<AudioChunk> =
        callbackFlow {
            val stream = openMicrophone()
            var release: () -> Unit = {}
            try {
                val context = runningContext()
                val sampleRate = context.sampleRate.toInt()
                val source = context.createMediaStreamSource(stream)
                val sink = createSink(context) { block -> trySend(AudioChunk(block.toFloatArray(), sampleRate)) }
                source.connect(sink.node)
                // Nodes not connected to the destination may never be rendered; the sink outputs silence.
                sink.node.connect(context.destination)
                release = {
                    source.disconnect()
                    sink.node.disconnect()
                    sink.release()
                }
                awaitClose()
            } finally {
                release()
                val tracks = stream.getTracks()
                for (i in 0 until tracks.length) tracks[i]?.stop()
            }
        }

    private suspend fun openMicrophone(): MediaStream =
        try {
            requestMicrophone().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw MicrophoneUnavailableException("Microphone access denied or no input device found", e)
        }

    private suspend fun createSink(
        context: AudioContext,
        onBlock: (Float32Array) -> Unit,
    ): CaptureSink {
        if (context.audioWorklet != null) {
            try {
                val loaded = workletLoaded ?: context.audioWorklet!!.addModule(WORKLET_URL).also { workletLoaded = it }
                loaded.await<JsAny?>()
                val node = AudioWorkletNode(context, WORKLET_NAME)
                node.port.onmessage = { event: MessageEvent -> onBlock(event.data!!.unsafeCast()) }
                return CaptureSink(node) { node.port.postMessage("stop".toJsString()) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                workletLoaded = null
            }
        }
        val node = context.createScriptProcessor(CHUNK_SAMPLES, 1, 1)
        node.onaudioprocess = { event -> onBlock(event.inputBuffer.getChannelData(0)) }
        return CaptureSink(node) { node.onaudioprocess = null }
    }

    private fun context(): AudioContext = context ?: AudioContext().also { context = it }

    private suspend fun runningContext(): AudioContext {
        val context = context()
        if (context.state != "running") context.resume().await<JsAny?>()
        return context
    }

    private class CaptureSink(
        val node: AudioNode,
        val release: () -> Unit,
    )

    private companion object {
        const val CHUNK_SAMPLES = 2048
        const val WORKLET_URL = "capture-worklet.js"
        const val WORKLET_NAME = "sounddrop-capture"
    }
}

/** Requests mono microphone audio with all voice processing off, which would otherwise remove our tones. */
private fun requestMicrophone(): Promise<MediaStream> =
    js(
        """navigator.mediaDevices.getUserMedia({
            audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false, channelCount: 1 }
        })""",
    )

/**
 * Resumes the context returned by [context] on every pointer or key press until it is running, then
 * removes the listeners. A rejected resume is ignored; the next gesture tries again.
 */
private fun unlockOnGesture(context: () -> AudioContext): Unit =
    js(
        """{
            const events = ["pointerdown", "keydown"];
            const remove = () => events.forEach((type) => document.removeEventListener(type, unlock));
            const unlock = () => {
                const audio = context();
                if (audio.state === "running") return remove();
                audio.resume().then(() => { if (audio.state === "running") remove(); }, () => {});
            };
            events.forEach((type) => document.addEventListener(type, unlock));
        }""",
    )

// Minimal Web Audio externals; kotlinx-browser does not cover this API.

private external class AudioContext : JsAny {
    val sampleRate: Double
    val state: String
    val destination: AudioNode
    val audioWorklet: AudioWorklet?

    fun resume(): Promise<JsAny?>

    fun createBuffer(
        numberOfChannels: Int,
        length: Int,
        sampleRate: Double,
    ): AudioBuffer

    fun createBufferSource(): AudioBufferSourceNode

    fun createMediaStreamSource(stream: MediaStream): AudioNode

    fun createScriptProcessor(
        bufferSize: Int,
        numberOfInputChannels: Int,
        numberOfOutputChannels: Int,
    ): ScriptProcessorNode
}

private open external class AudioNode : JsAny {
    fun connect(destination: AudioNode)

    fun disconnect()
}

private external class AudioBuffer : JsAny {
    fun copyToChannel(
        source: Float32Array,
        channelNumber: Int,
    )

    fun getChannelData(channel: Int): Float32Array
}

private external class AudioBufferSourceNode : AudioNode {
    var buffer: AudioBuffer?
    var onended: ((Event) -> Unit)?

    fun start()

    fun stop()
}

private external class AudioWorklet : JsAny {
    fun addModule(moduleUrl: String): Promise<JsAny?>
}

private external class AudioWorkletNode(
    context: AudioContext,
    name: String,
) : AudioNode {
    val port: MessagePort
}

private external class ScriptProcessorNode : AudioNode {
    var onaudioprocess: ((AudioProcessingEvent) -> Unit)?
}

private external class AudioProcessingEvent : Event {
    val inputBuffer: AudioBuffer
}
