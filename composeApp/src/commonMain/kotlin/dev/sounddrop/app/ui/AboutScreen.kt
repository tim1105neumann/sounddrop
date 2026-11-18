package dev.sounddrop.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sounddrop.modem.protocol.FrameHeader
import dev.sounddrop.modem.protocol.ModemProfile
import dev.sounddrop.modem.protocol.TonePlan
import kotlin.math.roundToInt

private val STEPS =
    listOf(
        "Chirp" to
            "Every transmission starts with a 0.2 s sweep from 1.5 to 5 kHz. The receiver finds it with an FFT " +
            "matched filter, which gives sample-exact timing even under noise.",
        "Two tones per byte" to
            "Each symbol plays one of ${TonePlan.TONES_PER_BAND} tones in a low band and one in a high band at the " +
            "same time: 4 bits each, so one symbol is exactly one byte. Neighbouring tones are Gray-coded.",
        "Guard time" to
            "The first third of every symbol is ignored, so the echo of the previous symbol in the room can fade.",
        "Goertzel filters" to
            "The receiver measures the exact tone frequencies instead of FFT bins, so 44.1 kHz and 48 kHz devices " +
            "understand each other without resampling.",
        "Reed–Solomon + CRC-32" to
            "About 50 % redundancy lets the receiver repair up to a quarter of the bytes — twice as many when it " +
            "knows which symbols sounded uncertain (erasures). A CRC-32 rejects anything it could not fix.",
    )

/** How SoundDrop works and where the code runs. */
@Composable
fun AboutScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("How it works", style = MaterialTheme.typography.headlineMedium)
        Text(
            "SoundDrop is an acoustic modem written in pure Kotlin. The same modem and the same Compose UI run on " +
                "Android, iOS, desktop and in the browser; only the few lines that talk to the speaker and the " +
                "microphone are platform-specific.",
            style = MaterialTheme.typography.bodyMedium,
        )
        STEPS.forEach { (title, body) ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(body, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Text("Numbers", style = MaterialTheme.typography.titleMedium)
        ModemProfile.entries.forEach { profile ->
            Text(
                "${profile.name}: ${(profile.symbolSeconds * 1000).roundToInt()} ms per byte, " +
                    "${profile.bitsPerSecond.roundToInt()} bit/s raw",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            "Up to ${FrameHeader.MAX_PAYLOAD} bytes per message, tones between " +
                "${TonePlan.bandA(0).roundToInt()} and ${TonePlan.maxFrequencyHz.roundToInt()} Hz.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
