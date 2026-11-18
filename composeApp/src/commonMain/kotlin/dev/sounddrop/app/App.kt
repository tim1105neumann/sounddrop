package dev.sounddrop.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.sounddrop.app.audio.AudioEngine
import dev.sounddrop.modem.payload.Payload
import dev.sounddrop.modem.protocol.ModemProfile

/** Root of the shared UI; each platform only supplies its [AudioEngine]. */
@Composable
fun App(engine: AudioEngine) {
    val viewModel = viewModel { TransceiverViewModel(engine) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("SoundDrop", style = MaterialTheme.typography.headlineMedium)
                SendPanel(
                    sending = state.sending,
                    onSend = viewModel::send,
                )
                ListenPanel(state, onToggle = viewModel::toggleListening)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.inbox, key = { it.id }) { message ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(message.payload.toString())
                                Text(
                                    "${message.profile} · ${message.correctedSymbols}/${message.symbolCount} symbols repaired",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SendPanel(
    sending: Boolean,
    onSend: (Payload, ModemProfile) -> Unit,
) {
    var text by remember { mutableStateOf("Hello from SoundDrop!") }
    var profile by remember { mutableStateOf(ModemProfile.Robust) }
    OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ModemProfile.entries.forEach { entry ->
            FilterChip(selected = entry == profile, onClick = { profile = entry }, label = { Text(entry.name) })
        }
        Button(onClick = { onSend(Payload.Text(text), profile) }, enabled = !sending) {
            Text(if (sending) "Sending…" else "Send")
        }
    }
}

@Composable
private fun ListenPanel(
    state: TransceiverState,
    onToggle: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onToggle) { Text(if (state.listening) "Stop listening" else "Listen") }
        Text(
            when (val r = state.reception) {
                Reception.Idle -> "Microphone off"
                Reception.Searching -> "Listening…"
                is Reception.Receiving -> "Receiving ${r.received}/${r.total}"
            },
        )
    }
    LinearProgressIndicator(progress = { (state.inputLevel * 4).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
}
