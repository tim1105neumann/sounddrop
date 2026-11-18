package dev.sounddrop.app.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.TargetDataLine

/** Desktop audio through `javax.sound.sampled`, using 16-bit PCM which every sound card supports. */
class JvmAudioEngine : AudioEngine {
    override suspend fun play(
        samples: FloatArray,
        sampleRate: Int,
    ): Unit =
        withContext(Dispatchers.IO) {
            val line = AudioSystem.getSourceDataLine(format(sampleRate))
            line.open()
            line.start()
            try {
                val bytes = ByteArray(samples.size * 2)
                for (i in samples.indices) {
                    val value = (samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
                    bytes[2 * i] = value.toByte()
                    bytes[2 * i + 1] = (value shr 8).toByte()
                }
                line.write(bytes, 0, bytes.size)
                line.drain()
            } finally {
                line.close()
            }
        }

    override fun capture(): Flow<AudioChunk> =
        flow {
            val (line, rate) = openMicrophone()
            line.start()
            try {
                val bytes = ByteArray(CHUNK_SAMPLES * 2)
                while (currentCoroutineContext().isActive) {
                    val read = line.read(bytes, 0, bytes.size)
                    val samples =
                        FloatArray(read / 2) { i ->
                            val value = (bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)
                            value.toShort() / 32768f
                        }
                    emit(AudioChunk(samples, rate))
                }
            } finally {
                line.stop()
                line.close()
            }
        }.flowOn(Dispatchers.IO)

    private fun openMicrophone(): Pair<TargetDataLine, Int> {
        var lastError: Exception? = null
        for (rate in PREFERRED_RATES) {
            try {
                val line = AudioSystem.getTargetDataLine(format(rate))
                line.open(format(rate), CHUNK_SAMPLES * 8)
                return line to rate
            } catch (e: LineUnavailableException) {
                lastError = e
            } catch (e: IllegalArgumentException) {
                lastError = e
            }
        }
        throw MicrophoneUnavailableException("No usable microphone found", lastError)
    }

    private fun format(sampleRate: Int) = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)

    private companion object {
        const val CHUNK_SAMPLES = 2048
        val PREFERRED_RATES = listOf(48_000, 44_100)
    }
}
