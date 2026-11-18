package dev.sounddrop.app.lab

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.sounddrop.app.ui.drawStretched
import dev.sounddrop.app.ui.heatColor
import dev.sounddrop.app.ui.spectrogramBitmap
import dev.sounddrop.modem.protocol.GrayCode
import dev.sounddrop.modem.protocol.ModemProfile
import dev.sounddrop.modem.protocol.TonePlan
import kotlin.math.roundToInt

/** The Loopback Lab: every stage of the modem, visualised, without any audio hardware. */
@Composable
fun LabScreen(
    modifier: Modifier = Modifier,
    viewModel: LabViewModel = viewModel { LabViewModel() },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Loopback Lab", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Send a message through a simulated room and watch every stage of the modem. " +
                "Turn up the noise and echo to see the error correction at work.",
            style = MaterialTheme.typography.bodyMedium,
        )
        LabControls(state, viewModel::updateSettings, onRun = viewModel::run, onBreak = viewModel::breakIt)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.result?.let { LabResultView(it, state.breakingPointDb) }
    }
}

@Composable
private fun LabControls(
    state: LabState,
    onChange: ((LabSettings) -> LabSettings) -> Unit,
    onRun: () -> Unit,
    onBreak: () -> Unit,
) {
    val settings = state.settings
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                value = settings.text,
                onValueChange = { text -> onChange { it.copy(text = text) } },
                label = { Text("Message") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModemProfile.entries.forEach { profile ->
                    FilterChip(
                        selected = settings.profile == profile,
                        onClick = { onChange { it.copy(profile = profile) } },
                        label = { Text("${profile.name} · ${profile.bitsPerSecond.roundToInt()} bit/s") },
                    )
                }
            }
            LabSlider("Signal-to-noise", "${settings.snrDb.roundToInt()} dB", settings.snrDb, -20f..40f) { v ->
                onChange { it.copy(snrDb = v) }
            }
            LabSlider("Echo level", "${(settings.echoGain * 100).roundToInt()} %", settings.echoGain, 0f..0.95f) { v ->
                onChange { it.copy(echoGain = v) }
            }
            LabSlider(
                "Echo delay",
                "${settings.echoDelayMillis.roundToInt()} ms",
                settings.echoDelayMillis,
                1f..60f,
            ) { v ->
                onChange { it.copy(echoDelayMillis = v) }
            }
            LabSlider(
                "Clock mismatch",
                "${settings.clockPpm.roundToInt()} ppm",
                settings.clockPpm,
                -1000f..1000f,
            ) { v ->
                onChange { it.copy(clockPpm = v) }
            }
            LabSwitch("Receiver records at 44.1 kHz (sender: 48 kHz)", settings.receiverAt44k) { v ->
                onChange { it.copy(receiverAt44k = v) }
            }
            LabSwitch("Loud noise burst (someone drops a plate)", settings.noiseBurst) { v ->
                onChange { it.copy(noiseBurst = v) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = onRun, enabled = !state.running) { Text("Transmit") }
                OutlinedButton(onClick = onBreak, enabled = !state.running) { Text("Break it") }
            }
        }
    }
}

@Composable
private fun LabSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.labelLarge)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun LabSwitch(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LabResultView(
    result: LabResult,
    breakingPointDb: Float?,
) {
    OutcomeCard(result, breakingPointDb)
    Stage("1 · Bytes on air", "Header, your data, a CRC-32 checksum and Reed–Solomon parity. One byte per symbol.") {
        ByteGrid(result.symbols)
    }
    Stage(
        "2 · What the microphone hears",
        "Spectrogram of the simulated recording, 1–6 kHz. Chirp first, then tone pairs.",
    ) {
        val image = remember(result) { spectrogramBitmap(result.spectrogram) }
        if (image != null) {
            Canvas(Modifier.fillMaxWidth().height(160.dp)) { drawStretched(image) }
        }
    }
    Stage(
        "3 · Tone decisions",
        "Each column is a symbol, each row one of the 32 tones. Brightness is the measured power; " +
            "rings mark the tones that were sent, red rings the ones the receiver got wrong.",
    ) {
        ToneGrid(result.symbols)
    }
}

@Composable
private fun OutcomeCard(
    result: LabResult,
    breakingPointDb: Float?,
) {
    val outcome = result.outcome
    val (title, detail) =
        when (outcome) {
            is LabOutcome.Decoded ->
                "Received: “${outcome.text}”" to
                    "${outcome.repaired} bytes repaired, ${outcome.erasures} flagged as uncertain"

            is LabOutcome.Failed -> "Lost: ${outcome.reason}" to "Too much damage for the error correction."
            LabOutcome.NoSync -> "Nothing heard" to "The receiver never recognised the preamble chirp."
        }
    val colors = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .background(if (outcome is LabOutcome.Decoded) colors.primaryContainer else colors.errorContainer)
                .padding(16.dp)
                .fillMaxWidth(),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium)
            val sync = result.syncScore?.let { "sync correlation ${(it * 100).roundToInt()} %" } ?: "no sync"
            Text(
                "SNR ${result.snrDb.roundToInt()} dB · air time ${(result.airtimeSeconds * 10).roundToInt() / 10.0} s · $sync",
                style = MaterialTheme.typography.labelMedium,
            )
            breakingPointDb?.let {
                Text(
                    "Breaking point: decoding failed at ${it.roundToInt()} dB SNR",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun Stage(
    title: String,
    description: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(description, style = MaterialTheme.typography.bodySmall)
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ByteGrid(symbols: List<LabSymbol>) {
    val colors = MaterialTheme.colorScheme
    FlowRow(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        symbols.forEach { symbol ->
            val background =
                when (symbol.role) {
                    ByteRole.Header -> colors.tertiary.copy(alpha = 0.35f)
                    ByteRole.Data -> colors.primary.copy(alpha = 0.35f)
                    ByteRole.Checksum -> colors.secondary.copy(alpha = 0.45f)
                    ByteRole.Parity -> colors.surfaceVariant
                }
            val border =
                when {
                    symbol.wrong && symbol.corrected -> Color(0xFF4CD787)
                    symbol.wrong -> colors.error
                    symbol.reading?.erased == true -> colors.outline
                    else -> Color.Transparent
                }
            Box(
                Modifier
                    .width(26.dp)
                    .background(background, RoundedCornerShape(4.dp))
                    .border(2.dp, border, RoundedCornerShape(4.dp))
                    .padding(vertical = 3.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    symbol.sent.toString(16).padStart(2, '0'),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Legend(colors.tertiary.copy(alpha = 0.35f), "header")
        Legend(colors.primary.copy(alpha = 0.35f), "data")
        Legend(colors.secondary.copy(alpha = 0.45f), "CRC-32")
        Legend(colors.surfaceVariant, "parity")
        Legend(Color(0xFF4CD787), "wrong, repaired", outlined = true)
        Legend(colors.error, "wrong", outlined = true)
    }
}

@Composable
private fun Legend(
    color: Color,
    label: String,
    outlined: Boolean = false,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val shape = RoundedCornerShape(3.dp)
        Box(
            Modifier
                .size(12.dp)
                .then(if (outlined) Modifier.border(2.dp, color, shape) else Modifier.background(color, shape)),
        )
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ToneGrid(symbols: List<LabSymbol>) {
    val errorColor = MaterialTheme.colorScheme.error
    val rows = TonePlan.TONES_PER_BAND * 2
    Canvas(Modifier.fillMaxWidth().height(180.dp).background(heatColor(0f))) {
        val cellW = size.width / symbols.size
        val cellH = size.height / rows
        symbols.forEachIndexed { x, symbol ->
            val reading = symbol.reading ?: return@forEachIndexed
            for (tone in 0 until TonePlan.TONES_PER_BAND) {
                // Band B (low nibble) on top, band A at the bottom: higher frequency = higher row.
                val rowA = rows - 1 - tone
                val rowB = TonePlan.TONES_PER_BAND - 1 - tone
                drawRect(heatColor(reading.powersA[tone]), Offset(x * cellW, rowA * cellH), Size(cellW, cellH))
                drawRect(heatColor(reading.powersB[tone]), Offset(x * cellW, rowB * cellH), Size(cellW, cellH))
            }
            val sentA = GrayCode.toneFor(symbol.sent shr 4)
            val sentB = GrayCode.toneFor(symbol.sent and 0x0F)
            val ring = if (symbol.wrong) errorColor else Color.White.copy(alpha = 0.8f)
            val stroke = Stroke(width = if (symbol.wrong) 2f else 1f)
            drawRect(ring, Offset(x * cellW, (rows - 1 - sentA) * cellH), Size(cellW, cellH), style = stroke)
            drawRect(
                ring,
                Offset(x * cellW, (TonePlan.TONES_PER_BAND - 1 - sentB) * cellH),
                Size(cellW, cellH),
                style = stroke,
            )
        }
    }
}
