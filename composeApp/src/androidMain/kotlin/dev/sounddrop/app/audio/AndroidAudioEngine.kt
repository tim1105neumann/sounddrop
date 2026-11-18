package dev.sounddrop.app.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Android audio through [AudioTrack] and [AudioRecord] in 32-bit float.
 *
 * Recording prefers the `UNPROCESSED` source (no noise suppression or automatic gain), falling back
 * to `VOICE_RECOGNITION`, which is also free of echo cancellation on most devices.
 *
 * @param requestPermission asks the user for `RECORD_AUDIO` and returns whether it was granted.
 */
class AndroidAudioEngine(
    context: Context,
    private val requestPermission: suspend () -> Boolean,
) : AudioEngine {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    override suspend fun play(
        samples: FloatArray,
        sampleRate: Int,
    ): Unit =
        withContext(Dispatchers.IO) {
            val track =
                AudioTrack
                    .Builder()
                    .setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    ).setAudioFormat(
                        AudioFormat
                            .Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    ).setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(samples.size * Float.SIZE_BYTES)
                    .build()
            try {
                track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                track.play()
                // A static track plays the whole buffer; wait until the playhead reaches its end.
                while (currentCoroutineContext().isActive && track.playbackHeadPosition < samples.size) {
                    delay(POLL_MILLIS)
                }
            } finally {
                track.stop()
                track.release()
            }
        }

    @SuppressLint("MissingPermission") // Checked through requestPermission right before recording.
    override fun capture(): Flow<AudioChunk> =
        flow {
            if (!requestPermission()) throw MicrophoneUnavailableException("Microphone permission was denied")
            val minBuffer =
                AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            val record =
                AudioRecord
                    .Builder()
                    .setAudioSource(audioSource())
                    .setAudioFormat(
                        AudioFormat
                            .Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build(),
                    ).setBufferSizeInBytes(maxOf(minBuffer, CHUNK_SAMPLES * Float.SIZE_BYTES) * 4)
                    .build()
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                throw MicrophoneUnavailableException("The microphone could not be opened")
            }
            record.startRecording()
            try {
                val buffer = FloatArray(CHUNK_SAMPLES)
                while (currentCoroutineContext().isActive) {
                    val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (read < 0) throw MicrophoneUnavailableException("Recording failed with error $read")
                    emit(AudioChunk(buffer.copyOf(read), record.sampleRate))
                }
            } finally {
                record.stop()
                record.release()
            }
        }.flowOn(Dispatchers.IO)

    private fun audioSource(): Int {
        val unprocessed = audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
        return if (unprocessed == "true") {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val CHUNK_SAMPLES = 2048
        const val POLL_MILLIS = 20L
    }
}
