package dev.sounddrop.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.sounddrop.app.Outgoing
import dev.sounddrop.app.TransceiverState
import dev.sounddrop.modem.payload.Payload
import dev.sounddrop.modem.protocol.FrameHeader
import dev.sounddrop.modem.protocol.FrameLayout
import dev.sounddrop.modem.protocol.ModemProfile
import kotlin.math.roundToInt

private enum class PayloadKind(
    val label: String,
) {
    Text("Text"),
    Link("Link"),
    Wifi("Wi-Fi"),
}

/** Compose a message and play it through the speaker. */
@Composable
fun SendScreen(
    state: TransceiverState,
    onSend: (Payload, ModemProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    var kind by rememberSaveable { mutableStateOf(PayloadKind.Text) }
    var text by rememberSaveable { mutableStateOf("Hello from SoundDrop! 👋") }
    var url by rememberSaveable { mutableStateOf("https://kotlinconf.com") }
    var ssid by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var profile by rememberSaveable { mutableStateOf(ModemProfile.Robust) }

    val payload =
        when (kind) {
            PayloadKind.Text -> Payload.Text(text)
            PayloadKind.Link -> Payload.Link(url.trim())
            PayloadKind.Wifi -> Payload.Wifi(ssid, password)
        }
    val size = Payload.encode(payload).size
    val fits = size <= FrameHeader.MAX_PAYLOAD

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Send", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Every device in earshot that has SoundDrop listening receives it — no network, no pairing.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PayloadKind.entries.forEach { entry ->
                FilterChip(selected = kind == entry, onClick = { kind = entry }, label = { Text(entry.label) })
            }
        }
        when (kind) {
            PayloadKind.Text ->
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Message") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )

            PayloadKind.Link ->
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

            PayloadKind.Wifi -> {
                OutlinedTextField(
                    value = ssid,
                    onValueChange = { ssid = it },
                    label = { Text("Network name (SSID)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModemProfile.entries.forEach { entry ->
                FilterChip(
                    selected = profile == entry,
                    onClick = { profile = entry },
                    label = { Text(if (entry == ModemProfile.Robust) "Robust (room)" else "Fast (close by)") },
                )
            }
        }
        val airtime = FrameLayout.airtimeSeconds(profile, size.coerceAtMost(FrameHeader.MAX_PAYLOAD))
        Text(
            "$size / ${FrameHeader.MAX_PAYLOAD} bytes · about ${(airtime * 10).roundToInt() / 10.0} s on air",
            style = MaterialTheme.typography.labelLarge,
            color = if (fits) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        )
        Button(
            onClick = { onSend(payload, profile) },
            enabled = fits && !state.sending && size > 0,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Text(if (state.sending) "Sending…" else "Send over sound")
        }
        state.outgoing?.let { OutgoingPreview(it) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

/** The spectrogram of what is being played, with a moving playhead. */
@Composable
private fun OutgoingPreview(outgoing: Outgoing) {
    val image = remember(outgoing) { spectrogramBitmap(outgoing.spectrogram) } ?: return
    val progress = remember(outgoing) { Animatable(0f) }
    LaunchedEffect(outgoing) {
        progress.animateTo(1f, tween((outgoing.seconds * 1000).toInt(), easing = LinearEasing))
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("On air: chirp, header, then one tone pair per byte", style = MaterialTheme.typography.labelLarge)
            Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                drawStretched(image)
                val x = size.width * progress.value
                drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
            }
        }
    }
}
