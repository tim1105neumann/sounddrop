package dev.sounddrop.modem.channel

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Impairments applied by [ChannelSimulator], modelling a speaker, a room and a microphone.
 *
 * @property snrDb signal-to-noise ratio of white noise relative to the signal's RMS level.
 * @property gain overall attenuation of the signal.
 * @property echoDelaySeconds delay of a single room reflection (0 disables it).
 * @property echoGain level of that reflection relative to the direct sound.
 * @property clockPpm clock mismatch between sender and receiver in parts per million.
 * @property receiverSampleRate sample rate of the receiving device, or `null` to match the sender.
 * @property burstAtSeconds start of a loud noise burst, measured from the start of the signal.
 * @property burstSeconds length of the burst (0 disables it).
 * @property burstLevel RMS level of the burst relative to the signal's RMS level.
 * @property paddingSeconds noisy silence added before and after the signal.
 */
public data class ChannelConditions(
    val snrDb: Double = 30.0,
    val gain: Double = 0.5,
    val echoDelaySeconds: Double = 0.0,
    val echoGain: Double = 0.0,
    val clockPpm: Double = 0.0,
    val receiverSampleRate: Int? = null,
    val burstAtSeconds: Double = 0.0,
    val burstSeconds: Double = 0.0,
    val burstLevel: Double = 3.0,
    val paddingSeconds: Double = 0.3,
)

/**
 * A software "room": passes audio through echo, noise, clock drift and sample-rate conversion so
 * the modem can be tested and explored on a single device without speakers or microphones.
 */
public class ChannelSimulator(
    private val random: Random = Random.Default,
) {
    /** The received audio and the sample rate it was "recorded" at. */
    public class Output(
        /** The received audio. */
        public val samples: FloatArray,
        /** Sample rate of [samples]. */
        public val sampleRate: Int,
    )

    /** Sends [samples] recorded at [sampleRate] through a channel with the given [conditions]. */
    public fun transmit(
        samples: FloatArray,
        sampleRate: Int,
        conditions: ChannelConditions,
    ): Output {
        val signalRms = rms(samples) * conditions.gain
        val padding = (conditions.paddingSeconds * sampleRate).roundToInt()
        val echoDelay = (conditions.echoDelaySeconds * sampleRate).roundToInt()
        val shaped = FloatArray(padding + samples.size + echoDelay + padding)
        for (i in samples.indices) {
            val direct = samples[i] * conditions.gain
            shaped[padding + i] += direct.toFloat()
            if (echoDelay > 0) shaped[padding + i + echoDelay] += (direct * conditions.echoGain).toFloat()
        }

        val receiverRate = conditions.receiverSampleRate ?: sampleRate
        val ratio = receiverRate.toDouble() / sampleRate * (1 + conditions.clockPpm * 1e-6)
        val received = if (ratio == 1.0) shaped else resample(shaped, ratio)

        val noiseRms = signalRms / 10.0.pow(conditions.snrDb / 20)
        val burstFrom = ((conditions.paddingSeconds + conditions.burstAtSeconds) * receiverRate).roundToInt()
        val burstTo = burstFrom + (conditions.burstSeconds * receiverRate).roundToInt()
        for (i in received.indices) {
            var noise = gaussian() * noiseRms
            if (i in burstFrom until burstTo) noise += gaussian() * signalRms * conditions.burstLevel
            received[i] = (received[i] + noise).toFloat().coerceIn(-1f, 1f)
        }
        return Output(received, receiverRate)
    }

    private fun gaussian(): Double {
        // Box–Muller transform.
        val u = 1.0 - random.nextDouble()
        return sqrt(-2 * ln(u)) * cos(2 * PI * random.nextDouble())
    }

    private fun rms(samples: FloatArray): Double {
        val active = samples.filter { it != 0f }
        if (active.isEmpty()) return 0.0
        return sqrt(active.sumOf { it.toDouble() * it } / active.size)
    }

    public companion object {
        /**
         * Resamples [input] by [ratio] (output rate / input rate) with Catmull–Rom cubic
         * interpolation, which is accurate enough for signals well below the Nyquist frequency.
         */
        public fun resample(
            input: FloatArray,
            ratio: Double,
        ): FloatArray {
            val outLength = (input.size * ratio).toInt()

            fun at(i: Int): Double = input[i.coerceIn(0, input.lastIndex)].toDouble()
            return FloatArray(outLength) { j ->
                val x = j / ratio
                val i = x.toInt()
                val t = x - i
                val p0 = at(i - 1)
                val p1 = at(i)
                val p2 = at(i + 1)
                val p3 = at(i + 2)
                val value =
                    p1 + 0.5 * t * (p2 - p0 + t * (2 * p0 - 5 * p1 + 4 * p2 - p3 + t * (3 * (p1 - p2) + p3 - p0)))
                value.toFloat()
            }
        }
    }
}
