package dev.sounddrop.app.audio

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map

/** Plays into nothing and "records" a prepared signal, chunk by chunk. */
class FakeAudioEngine(
    private val recording: FloatArray = FloatArray(0),
    private val recordingRate: Int = 48_000,
) : AudioEngine {
    val played = mutableListOf<FloatArray>()

    override suspend fun play(
        samples: FloatArray,
        sampleRate: Int,
    ) {
        played += samples
    }

    override fun capture(): Flow<AudioChunk> =
        recording
            .asList()
            .chunked(CHUNK)
            .asFlow()
            .map { AudioChunk(it.toFloatArray(), recordingRate) }

    private companion object {
        const val CHUNK = 2048
    }
}
