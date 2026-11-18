package dev.sounddrop.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.sounddrop.app.Outgoing
import dev.sounddrop.app.TransceiverState
import dev.sounddrop.modem.payload.Payload
import dev.sounddrop.modem.payload.SketchCodec
import dev.sounddrop.modem.payload.SketchPoint
import dev.sounddrop.modem.payload.encode
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
    Sketch("Sketch"),
}

/** Saves free-hand strokes as flat `x, y, x, y, …` arrays, so a drawing survives rotation. */
private val StrokesSaver =
    listSaver<SnapshotStateList<List<Offset>>, FloatArray>(
        save = { strokes -> strokes.map { stroke -> stroke.flatMap { listOf(it.x, it.y) }.toFloatArray() } },
        restore = { saved ->
            saved.map { flat -> List(flat.size / 2) { Offset(flat[2 * it], flat[2 * it + 1]) } }.toMutableStateList()
        },
    )

private fun fitSketch(strokes: List<List<Offset>>): List<List<SketchPoint>> =
    SketchCodec.fit(strokes.map { stroke -> stroke.map { it.x to it.y } }, FrameHeader.MAX_PAYLOAD)

private val ModemProfile.label: String
    get() =
        when (this) {
            ModemProfile.Robust -> "Robust (room)"
            ModemProfile.Fast -> "Fast (close by)"
            ModemProfile.Melodic -> "Melodic (a tune)"
        }

/** Compose a message and play it through the speaker. */
@OptIn(ExperimentalLayoutApi::class)
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
    val strokes = rememberSaveable(saver = StrokesSaver) { mutableStateListOf() }
    // Simplifying is expensive, so it runs once per finished stroke rather than on every move.
    var sketch by remember { mutableStateOf(fitSketch(strokes)) }

    val payload =
        when (kind) {
            PayloadKind.Text -> Payload.Text(text)
            PayloadKind.Link -> Payload.Link(url.trim())
            PayloadKind.Wifi -> Payload.Wifi(ssid, password)
            PayloadKind.Sketch -> Payload.Sketch(sketch)
        }
    val size = payload.encode().size
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

            PayloadKind.Sketch -> {
                SketchPad(
                    strokes = strokes,
                    onStrokeStart = { strokes += listOf(it) },
                    onStrokeMove = { point -> strokes[strokes.lastIndex] = strokes.last() + point },
                    onStrokeEnd = { sketch = fitSketch(strokes) },
                    preview = sketch,
                )
                Text(
                    "Draw something. The orange line is the simplified version that goes on air; " +
                        "every listening device in the room receives it at once.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(
                    onClick = {
                        strokes.clear()
                        sketch = emptyList()
                    },
                    enabled = strokes.isNotEmpty(),
                ) { Text("Clear drawing") }
            }

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
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModemProfile.entries.forEach { entry ->
                FilterChip(selected = profile == entry, onClick = { profile = entry }, label = { Text(entry.label) })
            }
        }
        if (profile == ModemProfile.Melodic) {
            Text(
                "Every byte becomes two notes of a pentatonic scale, so your message plays as a little tune.",
                style = MaterialTheme.typography.bodySmall,
            )
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
