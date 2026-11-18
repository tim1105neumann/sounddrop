package dev.sounddrop.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.sounddrop.app.audio.AudioEngine
import dev.sounddrop.app.audio.MicrophoneUnavailableException
import dev.sounddrop.modem.dsp.Spectrogram
import dev.sounddrop.modem.payload.Payload
import dev.sounddrop.modem.payload.encode
import dev.sounddrop.modem.payload.type
import dev.sounddrop.modem.protocol.FrameHeader
import dev.sounddrop.modem.protocol.ModemProfile
import dev.sounddrop.modem.rx.DecoderEvent
import dev.sounddrop.modem.rx.StreamingDecoder
import dev.sounddrop.modem.tx.Modulator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/** Everything the send and listen screens show, except the fast-changing input level. */
data class TransceiverState(
    val sending: Boolean = false,
    val reception: Reception = Reception.Idle,
    val inbox: List<ReceivedMessage> = emptyList(),
    val outgoing: Outgoing? = null,
    val failedFrames: Int = 0,
    val error: String? = null,
) {
    /** Whether the microphone is on. */
    val listening: Boolean get() = reception != Reception.Idle
}

/** The transmission currently being played. */
class Outgoing(
    /** What it looks like on air, for the send screen's preview. */
    val spectrogram: List<FloatArray>,
    /** Playback duration. */
    val seconds: Double,
)

/** What the receiver is doing right now. */
sealed interface Reception {
    /** The microphone is off. */
    data object Idle : Reception

    /** Listening for a preamble. */
    data object Searching : Reception

    /** A frame is arriving; [received] of [total] symbols are in. */
    data class Receiving(
        val received: Int,
        val total: Int,
    ) : Reception
}

/** A successfully decoded transmission. */
data class ReceivedMessage(
    val id: Int,
    val payload: Payload,
    val profile: ModemProfile,
    val correctedSymbols: Int,
    val symbolCount: Int,
)

/** Returns a user-facing error if a payload of [size] bytes does not fit into one frame. */
fun payloadSizeError(size: Int): String? =
    if (size > FrameHeader.MAX_PAYLOAD) "Message is $size bytes, the limit is ${FrameHeader.MAX_PAYLOAD}" else null

/** Connects the shared modem with the platform [AudioEngine]. */
class TransceiverViewModel(
    private val engine: AudioEngine,
) : ViewModel() {
    private val _state = MutableStateFlow(TransceiverState())

    /** Screen state; changes a few times per transmission. */
    val state: StateFlow<TransceiverState> = _state.asStateFlow()

    private val _inputLevel = MutableStateFlow(0f)

    /** RMS level of the microphone, updated for every audio chunk while listening. */
    val inputLevel: StateFlow<Float> = _inputLevel.asStateFlow()

    private val _spectrum =
        MutableSharedFlow<FloatArray>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Live spectrogram columns of the microphone signal while listening. */
    val spectrum: SharedFlow<FloatArray> = _spectrum.asSharedFlow()

    private var sendJob: Job? = null
    private var listenJob: Job? = null
    private var nextMessageId = 0

    /** Encodes and plays [payload]. The receiver ignores the microphone meanwhile. */
    fun send(
        payload: Payload,
        profile: ModemProfile,
    ) {
        if (sendJob?.isActive == true) return
        val bytes = payload.encode()
        payloadSizeError(bytes.size)?.let { error ->
            _state.update { it.copy(error = error) }
            return
        }
        // Mark the transmission as started right away, so a second tap cannot start another one.
        _state.update { it.copy(sending = true, error = null) }
        sendJob =
            viewModelScope.launch {
                try {
                    val (samples, outgoing) =
                        withContext(Dispatchers.Default) {
                            val samples = Modulator(OUTPUT_SAMPLE_RATE).modulate(bytes, payload.type, profile).samples
                            samples to
                                Outgoing(
                                    spectrogram = Spectrogram.of(samples, OUTPUT_SAMPLE_RATE),
                                    seconds = samples.size.toDouble() / OUTPUT_SAMPLE_RATE,
                                )
                        }
                    _state.update { it.copy(outgoing = outgoing) }
                    engine.play(samples, OUTPUT_SAMPLE_RATE)
                    // Let the room echo of our own signal fade before listening again.
                    delay(ECHO_TAIL_MILLIS)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(error = "Playback failed: ${e.message}") }
                } finally {
                    _state.update { it.copy(sending = false, outgoing = null) }
                }
            }
    }

    /** Starts or stops the microphone. */
    fun toggleListening() {
        if (listenJob != null) stopListening() else startListening()
    }

    /** Forgets every received message. */
    fun clearInbox() {
        _state.update { it.copy(inbox = emptyList()) }
    }

    private fun startListening() {
        _state.update { it.copy(reception = Reception.Searching, error = null) }
        val receiver = Receiver()
        // Collected on the main thread; only the signal processing runs on Dispatchers.Default.
        listenJob =
            viewModelScope.launch {
                engine
                    .capture()
                    .map { chunk -> receiver.process(chunk.samples, chunk.sampleRate, muted = state.value.sending) }
                    .flowOn(Dispatchers.Default)
                    .catch { e ->
                        val message =
                            if (e is MicrophoneUnavailableException) e.message else "Microphone error: ${e.message}"
                        _state.update { it.copy(error = message) }
                    }.onCompletion { cause -> if (cause !is CancellationException) stopListening() }
                    .collect(::apply)
            }
    }

    private fun stopListening() {
        listenJob?.cancel()
        listenJob = null
        _inputLevel.value = 0f
        _state.update { it.copy(reception = Reception.Idle) }
    }

    private fun apply(result: Receiver.Result) {
        _inputLevel.value = result.level
        result.columns.forEach(_spectrum::tryEmit)
        if (result.muted) _state.update { it.copy(reception = Reception.Searching) }
        result.events.forEach(::onEvent)
    }

    private fun onEvent(event: DecoderEvent) {
        when (event) {
            is DecoderEvent.SymbolDecoded ->
                if (event.section == DecoderEvent.Section.Payload) {
                    _state.update { it.copy(reception = Reception.Receiving(event.index + 1, event.count)) }
                }

            is DecoderEvent.FrameDecoded -> {
                val frame = event.frame
                val payload = Payload.decode(frame.type, frame.payload) ?: return
                val message =
                    ReceivedMessage(
                        id = nextMessageId++,
                        payload = payload,
                        profile = frame.profile,
                        correctedSymbols = frame.correctedSymbols.size,
                        symbolCount = frame.symbolCount,
                    )
                _state.update { it.copy(inbox = listOf(message) + it.inbox, reception = Reception.Searching) }
            }

            is DecoderEvent.FrameFailed -> {
                // An invalid header is usually just noise that resembled the chirp; don't count it.
                val heard = if (event.reason == DecoderEvent.FailureReason.InvalidHeader) 0 else 1
                _state.update { it.copy(reception = Reception.Searching, failedFrames = it.failedFrames + heard) }
            }

            is DecoderEvent.SyncFound, is DecoderEvent.HeaderDecoded -> Unit
        }
    }

    /** Decoder and spectrogram for one listening session; adapts to the recording sample rate. */
    private class Receiver {
        private var decoder: StreamingDecoder? = null
        private var spectrogram: Spectrogram? = null

        /** What one microphone chunk produced. */
        class Result(
            val level: Float,
            val columns: List<FloatArray>,
            val events: List<DecoderEvent>,
            val muted: Boolean,
        )

        fun process(
            samples: FloatArray,
            sampleRate: Int,
            muted: Boolean,
        ): Result {
            val decoder = decoder?.takeIf { it.sampleRate == sampleRate } ?: StreamingDecoder(sampleRate)
            val spectrogram = spectrogram?.takeIf { it.sampleRate == sampleRate } ?: Spectrogram(sampleRate)
            this.decoder = decoder
            this.spectrogram = spectrogram
            val columns = spectrogram.feed(samples)
            // While we transmit ourselves, the microphone only hears our own signal.
            if (muted) decoder.reset()
            val events = if (muted) emptyList() else decoder.feed(samples)
            return Result(level = if (muted) 0f else rms(samples), columns = columns, events = events, muted = muted)
        }

        private fun rms(samples: FloatArray): Float {
            if (samples.isEmpty()) return 0f
            var sum = 0.0
            for (s in samples) sum += s * s
            return sqrt(sum / samples.size).toFloat()
        }
    }

    private companion object {
        /** Every platform can play 48 kHz audio; receivers adapt to whatever rate they record at. */
        const val OUTPUT_SAMPLE_RATE = 48_000
        const val ECHO_TAIL_MILLIS = 250L
    }
}
