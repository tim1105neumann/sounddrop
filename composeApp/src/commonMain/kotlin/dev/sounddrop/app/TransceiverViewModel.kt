package dev.sounddrop.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.sounddrop.app.audio.AudioEngine
import dev.sounddrop.app.audio.MicrophoneUnavailableException
import dev.sounddrop.modem.payload.Payload
import dev.sounddrop.modem.protocol.FrameHeader
import dev.sounddrop.modem.protocol.ModemProfile
import dev.sounddrop.modem.rx.DecoderEvent
import dev.sounddrop.modem.rx.StreamingDecoder
import dev.sounddrop.modem.tx.Modulator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/** Everything the send and listen screens show. */
data class TransceiverState(
    val listening: Boolean = false,
    val sending: Boolean = false,
    val inputLevel: Float = 0f,
    val reception: Reception = Reception.Idle,
    val inbox: List<ReceivedMessage> = emptyList(),
    val error: String? = null,
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

/** Connects the shared modem with the platform [AudioEngine]. */
class TransceiverViewModel(
    private val engine: AudioEngine,
) : ViewModel() {
    private val mutableState = MutableStateFlow(TransceiverState())
    val state: StateFlow<TransceiverState> = mutableState.asStateFlow()

    private var listenJob: Job? = null
    private var nextMessageId = 0

    /** Encodes and plays [payload]. The receiver ignores the microphone meanwhile. */
    fun send(
        payload: Payload,
        profile: ModemProfile,
    ) {
        if (state.value.sending) return
        val bytes = Payload.encode(payload)
        if (bytes.size > FrameHeader.MAX_PAYLOAD) {
            mutableState.update {
                it.copy(
                    error = "Message is ${bytes.size} bytes, the limit is ${FrameHeader.MAX_PAYLOAD}",
                )
            }
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(sending = true, error = null) }
            try {
                val transmission = Modulator(OUTPUT_SAMPLE_RATE).modulate(bytes, Payload.typeOf(payload), profile)
                engine.play(transmission.samples, OUTPUT_SAMPLE_RATE)
                // Let the room echo of our own signal fade before listening again.
                delay(ECHO_TAIL_MILLIS)
            } finally {
                mutableState.update { it.copy(sending = false) }
            }
        }
    }

    /** Starts or stops the microphone. */
    fun toggleListening() {
        if (listenJob != null) stopListening() else startListening()
    }

    /** Forgets every received message. */
    fun clearInbox() {
        mutableState.update { it.copy(inbox = emptyList()) }
    }

    private fun startListening() {
        mutableState.update { it.copy(listening = true, reception = Reception.Searching, error = null) }
        listenJob =
            viewModelScope.launch(Dispatchers.Default) {
                var decoder: StreamingDecoder? = null
                engine
                    .capture()
                    .catch { e ->
                        val message = if (e is MicrophoneUnavailableException) e.message else "Microphone error: ${e.message}"
                        mutableState.update { it.copy(error = message) }
                    }.collect { chunk ->
                        val current =
                            decoder?.takeIf { it.sampleRate == chunk.sampleRate }
                                ?: StreamingDecoder(chunk.sampleRate).also { decoder = it }
                        if (state.value.sending) {
                            current.reset()
                            mutableState.update { it.copy(inputLevel = 0f, reception = Reception.Searching) }
                        } else {
                            val events = current.feed(chunk.samples)
                            mutableState.update { it.copy(inputLevel = rms(chunk.samples)) }
                            events.forEach(::onEvent)
                        }
                    }
                stopListening()
            }
    }

    private fun stopListening() {
        listenJob?.cancel()
        listenJob = null
        mutableState.update { it.copy(listening = false, reception = Reception.Idle, inputLevel = 0f) }
    }

    private fun onEvent(event: DecoderEvent) {
        when (event) {
            is DecoderEvent.SymbolDecoded ->
                if (event.section == DecoderEvent.Section.Payload) {
                    mutableState.update { it.copy(reception = Reception.Receiving(event.index + 1, event.count)) }
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
                mutableState.update { it.copy(inbox = listOf(message) + it.inbox, reception = Reception.Searching) }
            }

            is DecoderEvent.FrameFailed -> mutableState.update { it.copy(reception = Reception.Searching) }

            is DecoderEvent.SyncFound, is DecoderEvent.HeaderDecoded -> Unit
        }
    }

    private fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) sum += s * s
        return sqrt(sum / samples.size).toFloat()
    }

    private companion object {
        /** Every platform can play 48 kHz audio; receivers adapt to whatever rate they record at. */
        const val OUTPUT_SAMPLE_RATE = 48_000
        const val ECHO_TAIL_MILLIS = 250L
    }
}
