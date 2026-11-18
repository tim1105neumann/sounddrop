package dev.sounddrop.app.lab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.sounddrop.app.payloadSizeError
import dev.sounddrop.modem.channel.ChannelConditions
import dev.sounddrop.modem.channel.ChannelSimulator
import dev.sounddrop.modem.dsp.Spectrogram
import dev.sounddrop.modem.payload.Payload
import dev.sounddrop.modem.payload.encode
import dev.sounddrop.modem.payload.type
import dev.sounddrop.modem.protocol.FrameHeader
import dev.sounddrop.modem.protocol.ModemProfile
import dev.sounddrop.modem.rx.DecoderEvent
import dev.sounddrop.modem.rx.StreamingDecoder
import dev.sounddrop.modem.rx.SymbolReading
import dev.sounddrop.modem.tx.Modulator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Knobs of the simulated room. */
data class LabSettings(
    val text: String = "Hello KotlinConf 2027!",
    val profile: ModemProfile = ModemProfile.Robust,
    val snrDb: Float = 20f,
    val echoGain: Float = 0f,
    val echoDelayMillis: Float = 15f,
    val clockPpm: Float = 0f,
    val receiverAt44k: Boolean = false,
    val noiseBurst: Boolean = false,
)

/** Role of a byte inside a frame, used to colour it. */
enum class ByteRole { Header, Data, Checksum, Parity }

/** One symbol of the experiment: what was sent and what the receiver made of it. */
class LabSymbol(
    /** The byte that went on air. */
    val sent: Int,
    /** Which part of the frame the byte belongs to. */
    val role: ByteRole,
    /** What the receiver heard, or `null` if it never got that far (for example no sync). */
    val reading: SymbolReading?,
    /** Whether Reed–Solomon had to repair this byte. */
    val corrected: Boolean,
) {
    /** The receiver heard a different byte than the one that was sent. */
    val wrong: Boolean get() = reading != null && reading.value != sent
}

/** Final verdict of an experiment. */
sealed interface LabOutcome {
    /** The message came through; [repaired] bytes were fixed by error correction. */
    data class Decoded(
        val text: String,
        val repaired: Int,
        val erasures: Int,
    ) : LabOutcome

    /** A frame was found but could not be recovered. */
    data class Failed(
        val reason: DecoderEvent.FailureReason,
    ) : LabOutcome

    /** The receiver never recognised the preamble. */
    data object NoSync : LabOutcome
}

/** Everything the Lab shows about one run. */
class LabResult(
    val symbols: List<LabSymbol>,
    val spectrogram: List<FloatArray>,
    val outcome: LabOutcome,
    val syncScore: Float?,
    val airtimeSeconds: Double,
    val snrDb: Float,
)

/** State of the Lab screen. */
data class LabState(
    val settings: LabSettings = LabSettings(),
    val running: Boolean = false,
    val result: LabResult? = null,
    val breakingPointDb: Float? = null,
    val error: String? = null,
)

/**
 * Runs the real modem end to end through [ChannelSimulator], entirely in memory. This lets anyone
 * explore the protocol on a single device, without speakers or a microphone.
 */
class LabViewModel : ViewModel() {
    private val _state = MutableStateFlow(LabState())

    /** Screen state. */
    val state: StateFlow<LabState> = _state.asStateFlow()

    private var job: Job? = null

    /** Changes the experiment settings. */
    fun updateSettings(transform: (LabSettings) -> LabSettings) {
        _state.update { it.copy(settings = transform(it.settings)) }
    }

    /** Sends the message once through the simulated room. */
    fun run() {
        launchExclusive {
            _state.update { it.copy(result = simulate(state.value.settings), breakingPointDb = null) }
        }
    }

    /** Lowers the signal-to-noise ratio step by step until decoding fails. */
    fun breakIt() {
        launchExclusive {
            _state.update { it.copy(breakingPointDb = null) }
            var snr = BREAK_START_DB
            while (snr >= BREAK_END_DB) {
                updateSettings { it.copy(snrDb = snr) }
                val result = simulate(state.value.settings)
                _state.update { it.copy(result = result) }
                if (result.outcome !is LabOutcome.Decoded) {
                    _state.update { it.copy(breakingPointDb = snr) }
                    break
                }
                delay(BREAK_STEP_MILLIS)
                snr -= BREAK_STEP_DB
            }
        }
    }

    private fun launchExclusive(block: suspend () -> Unit) {
        payloadSizeError(Payload.Text(state.value.settings.text).encode().size)?.let { error ->
            _state.update { it.copy(error = error) }
            return
        }
        val previous = job
        job =
            viewModelScope.launch {
                // Wait for the previous run, so its cleanup cannot reset `running` for this one.
                previous?.cancelAndJoin()
                _state.update { it.copy(running = true, error = null) }
                try {
                    block()
                } finally {
                    _state.update { it.copy(running = false) }
                }
            }
    }

    private suspend fun simulate(settings: LabSettings): LabResult =
        withContext(Dispatchers.Default) {
            val payload = Payload.Text(settings.text)
            val tx =
                Modulator(SENDER_RATE).modulate(payload.encode(), payload.type, settings.profile)
            val conditions =
                ChannelConditions(
                    snrDb = settings.snrDb.toDouble(),
                    echoGain = settings.echoGain.toDouble(),
                    echoDelaySeconds = if (settings.echoGain > 0f) settings.echoDelayMillis / 1000.0 else 0.0,
                    clockPpm = settings.clockPpm.toDouble(),
                    receiverSampleRate = if (settings.receiverAt44k) 44_100 else null,
                    burstAtSeconds = 1.0,
                    burstSeconds = if (settings.noiseBurst) 0.5 else 0.0,
                    burstLevel = 6.0,
                )
            val rx = ChannelSimulator().transmit(tx.samples, SENDER_RATE, conditions)
            ensureActive()
            val decoder = StreamingDecoder(rx.sampleRate)
            val events = decoder.feed(rx.samples) + decoder.feed(FloatArray(rx.sampleRate))
            analyse(tx, events, Spectrogram.of(rx.samples, rx.sampleRate), settings)
        }

    private fun analyse(
        tx: Modulator.Transmission,
        events: List<DecoderEvent>,
        spectrogram: List<FloatArray>,
        settings: LabSettings,
    ): LabResult {
        val headerReadings = arrayOfNulls<SymbolReading>(tx.headerSymbols.size)
        val payloadReadings = arrayOfNulls<SymbolReading>(tx.payloadSymbols.size)
        var outcome: LabOutcome = LabOutcome.NoSync
        var syncScore: Float? = null
        var corrected = emptySet<Int>()
        // Only the first frame matters; a simulated run contains exactly one.
        for (event in events) {
            when (event) {
                is DecoderEvent.SyncFound -> if (syncScore == null) syncScore = event.score

                is DecoderEvent.SymbolDecoded ->
                    when (event.section) {
                        DecoderEvent.Section.Header -> headerReadings.setIfInRange(event.index, event.reading)
                        DecoderEvent.Section.Payload -> payloadReadings.setIfInRange(event.index, event.reading)
                    }

                is DecoderEvent.FrameDecoded -> {
                    corrected = event.frame.correctedSymbols.toSet()
                    outcome =
                        LabOutcome.Decoded(
                            text = event.frame.payload.decodeToString(),
                            repaired = event.frame.correctedSymbols.size,
                            erasures = event.frame.erasedSymbols.size,
                        )
                    break
                }

                is DecoderEvent.FrameFailed -> {
                    outcome = LabOutcome.Failed(event.reason)
                    if (event.reason != DecoderEvent.FailureReason.InvalidHeader) break
                }

                is DecoderEvent.HeaderDecoded -> Unit
            }
        }
        val dataSize = tx.header.length
        val symbols =
            tx.headerSymbols.mapIndexed { i, byte ->
                LabSymbol(byte.toInt() and 0xFF, ByteRole.Header, headerReadings[i], corrected = false)
            } +
                tx.payloadSymbols.mapIndexed { i, byte ->
                    val role =
                        when {
                            i < dataSize -> ByteRole.Data
                            i < dataSize + FrameHeader.CRC_SIZE -> ByteRole.Checksum
                            else -> ByteRole.Parity
                        }
                    LabSymbol(byte.toInt() and 0xFF, role, payloadReadings[i], corrected = i in corrected)
                }
        return LabResult(
            symbols = symbols,
            spectrogram = spectrogram,
            outcome = outcome,
            syncScore = syncScore,
            airtimeSeconds = tx.samples.size.toDouble() / SENDER_RATE,
            snrDb = settings.snrDb,
        )
    }

    private fun Array<SymbolReading?>.setIfInRange(
        index: Int,
        reading: SymbolReading,
    ) {
        if (index in indices && this[index] == null) this[index] = reading
    }

    private companion object {
        const val SENDER_RATE = 48_000
        const val BREAK_START_DB = 12f
        const val BREAK_END_DB = -20f
        const val BREAK_STEP_DB = 2f
        const val BREAK_STEP_MILLIS = 350L
    }
}
