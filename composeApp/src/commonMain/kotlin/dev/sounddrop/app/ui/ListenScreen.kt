package dev.sounddrop.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.sounddrop.app.ReceivedMessage
import dev.sounddrop.app.Reception
import dev.sounddrop.app.TransceiverState
import dev.sounddrop.modem.payload.Payload
import kotlinx.coroutines.flow.Flow

/** Live microphone spectrogram and the inbox of received messages. */
@Composable
fun ListenScreen(
    state: TransceiverState,
    spectrum: Flow<FloatArray>,
    onToggleListening: () -> Unit,
    onClearInbox: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val waterfall = remember { WaterfallState() }
    LaunchedEffect(spectrum) { spectrum.collect(waterfall::push) }

    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Listen", style = MaterialTheme.typography.headlineMedium)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(onClick = onToggleListening) {
                        Text(if (state.listening) "Stop listening" else "Start listening")
                    }
                    Text(statusText(state), style = MaterialTheme.typography.titleSmall)
                }
                Waterfall(
                    waterfall,
                    Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(12.dp)),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Input level", style = MaterialTheme.typography.labelMedium)
                    LinearProgressIndicator(
                        progress = { (state.inputLevel * 5).coerceIn(0f, 1f) },
                        modifier = Modifier.weight(1f),
                    )
                }
                (state.reception as? Reception.Receiving)?.let { receiving ->
                    LinearProgressIndicator(
                        progress = { receiving.received.toFloat() / receiving.total },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                    )
                }
                if (state.failedFrames > 0) {
                    Text(
                        "${state.failedFrames} transmission(s) heard but too damaged — try moving closer or the Robust mode.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Inbox", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (state.inbox.isNotEmpty()) TextButton(onClick = onClearInbox) { Text("Clear") }
                }
                if (state.inbox.isEmpty()) {
                    Text(
                        "Nothing received yet. Start listening, then press Send on another device.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        items(state.inbox, key = { it.id }) { InboxItem(it) }
    }
}

private fun statusText(state: TransceiverState): String =
    when (val reception = state.reception) {
        Reception.Idle -> "Microphone off"
        Reception.Searching -> if (state.sending) "Paused while sending" else "Listening for a chirp…"
        is Reception.Receiving -> "Receiving… ${reception.received} / ${reception.total}"
    }

@Composable
private fun InboxItem(message: ReceivedMessage) {
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (val payload = message.payload) {
                is Payload.Text -> {
                    Text(payload.text, style = MaterialTheme.typography.bodyLarge)
                    Row {
                        OutlinedButton(
                            onClick = { clipboard.setText(AnnotatedString(payload.text)) },
                        ) { Text("Copy") }
                    }
                }

                is Payload.Link -> {
                    Text(
                        payload.url,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { runCatching { uriHandler.openUri(payload.url) } }) { Text("Open") }
                        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(payload.url)) }) { Text("Copy") }
                    }
                }

                is Payload.Wifi -> {
                    Text("Wi-Fi “${payload.ssid}”", style = MaterialTheme.typography.bodyLarge)
                    Text("Security: ${payload.security}", style = MaterialTheme.typography.bodySmall)
                    Row {
                        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(payload.password)) }) {
                            Text("Copy password")
                        }
                    }
                }

                is Payload.Sketch -> {
                    Text("Sketch", style = MaterialTheme.typography.bodyLarge)
                    SketchReplay(payload.strokes)
                }
            }
            Text(
                "${message.profile} mode · ${message.correctedSymbols} of ${message.symbolCount} symbols repaired",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
