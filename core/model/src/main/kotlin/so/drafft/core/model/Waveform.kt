package so.drafft.core.model

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Deterministic pseudo-random waveform, so a voice intro without levels still looks distinct and stable. */
object Waveform {
    fun seeded(seed: String, count: Int): List<Float> {
        var h = 1469598103934665603uL
        for (b in seed.encodeToByteArray()) h = (h xor b.toUByte().toULong()) * 1099511628211uL
        return List(count) { i ->
            h = h * 6364136223846793005uL + 1442695040888963407uL
            val r = ((h shr 33) % 1000uL).toFloat() / 1000f
            val envelope = sin(i.toFloat() / count * PI.toFloat()) * 0.55f + 0.45f
            max(0.12f, min(1f, r * envelope + 0.1f))
        }
    }
}
