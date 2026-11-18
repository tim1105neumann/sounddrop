package dev.sounddrop.app.audio

import kotlinx.coroutines.flow.Flow

/**
 * The only platform-specific part of SoundDrop: getting samples to the speaker and from the
 * microphone. Everything else, from the modem to the UI, is shared code.
 */
interface AudioEngine {
    /** Plays mono [samples] in `-1..1` at [sampleRate] and suspends until playback finished. */
    suspend fun play(
        samples: FloatArray,
        sampleRate: Int,
    )

    /**
     * Microphone audio as a cold flow: recording starts when collection starts and stops when the
     * collector is cancelled. The flow fails with [MicrophoneUnavailableException] if access is
     * denied or no input device exists.
     */
    fun capture(): Flow<AudioChunk>
}

/** A block of mono microphone samples in `-1..1`. */
class AudioChunk(
    val samples: FloatArray,
    val sampleRate: Int,
)

/** Thrown by [AudioEngine.capture] when the microphone cannot be used. */
class MicrophoneUnavailableException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
