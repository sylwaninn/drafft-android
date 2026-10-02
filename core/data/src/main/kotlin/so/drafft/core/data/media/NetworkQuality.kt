package so.drafft.core.data.media

// The path (which network, Data Saver) comes from the Android side (`installImages` in core:ui), the
// speed from the photo downloads themselves.

/**
 * How the connection is doing, for what the app fetches first and how big (`ImageStore`, `PhotoWindow`).
 *
 * - [isSlow]: Data Saver, or photos measured arriving slowly (under about 1.6 Mbit/s each, back to
 *   normal above 3.2: a gap so one slow photo doesn't flip it back and forth). Full copies are asked a
 *   step lighter.
 * - [isLimited]: slow, or not measured yet on this network (at launch, after a switch from Wi-Fi to
 *   cellular): small copies first and few downloads at once, so nothing starts with a dozen full photos
 *   sharing a line nobody knows. On a fast line the first photo clears it in a fraction of a second.
 *
 * The speed is an average that follows the last few downloads ([download]). A metered network alone
 * says nothing of it.
 */
class NetworkQuality {
    private val lock = Any()
    private val notifying = Any()
    private var network: String? = null
    private var constrained = false

    /** Bytes per second, averaged over recent photo downloads. */
    private var speed: Double? = null
    private var slow = false
    private var limitedNow = true
    private val observers = mutableListOf<(Boolean) -> Unit>()

    val isLimited: Boolean get() = synchronized(lock) { limitedNow }

    val isSlow: Boolean get() = synchronized(lock) { constrained || slow }

    /**
     * Called now with [isLimited] (limited until a speed is known), then on every change of it, in order
     * (any thread).
     */
    fun onChange(observer: (Boolean) -> Unit) = synchronized(notifying) {
        val now = synchronized(lock) {
            observers += observer
            limitedNow
        }
        observer(now)
    }

    /** The network in use ([network]: an identity, null without one) and [constrained] (Data Saver). */
    fun path(network: String?, constrained: Boolean) {
        synchronized(lock) {
            // Another network: what was measured on the last one says nothing of this one.
            if (network != this.network) {
                this.network = network
                speed = null
                slow = false
            }
            this.constrained = constrained
        }
        changed()
    }

    /**
     * Times a photo download from its first byte (asking for it, renewing its link, the latency: none of
     * that is the line's speed). Recorded once: a second after the first byte (a slow line shows within
     * the first download, finished or not), or at its end for a shorter one of some size. A failed or
     * cancelled download, or one too small, says nothing of the line.
     */
    fun download(): Download = Download()

    /** One photo download's bytes so far, and whether its speed was recorded (once). */
    inner class Download internal constructor() {
        private var firstByte: Double? = null
        private var first = 0L
        private var total = 0L
        private var done = false

        /** [bytes] more arrived at [now] (seconds on a monotonic clock). */
        fun received(bytes: Long, now: Double = monotonicSeconds()) {
            val speed = synchronized(this) {
                total += bytes
                val start = firstByte
                if (start == null) {
                    firstByte = now
                    first = bytes
                    return
                }
                if (done || now - start < 1 || total < MIN_SAMPLE) return
                done = true
                (total - first) / (now - start)
            }
            record(speed)
        }

        /** The download ended at [now]: read to its end, or [failed] (an error, or cancelled). */
        fun ended(failed: Boolean, now: Double = monotonicSeconds()) {
            val speed = synchronized(this) {
                val start = firstByte
                if (done || failed || start == null || total < MIN_END || now <= start) {
                    done = true
                    return
                }
                done = true
                (total - first) / (now - start)
            }
            record(speed)
        }
    }

    private fun record(bytesPerSecond: Double) {
        synchronized(lock) {
            val average = speed?.let { it * 0.6 + bytesPerSecond * 0.4 } ?: bytesPerSecond
            speed = average
            if (average < SLOW_BELOW) slow = true else if (average > FAST_ABOVE) slow = false
        }
        changed()
    }

    /** Observers hear the changes one at a time, in the order they happened. */
    private fun changed() = synchronized(notifying) {
        val (limited, notify) = synchronized(lock) {
            val limited = constrained || slow || speed == null
            val notify = if (limited != limitedNow) observers.toList() else emptyList()
            limitedNow = limited
            limited to notify
        }
        for (observer in notify) observer(limited)
    }

    companion object {
        val shared = NetworkQuality()

        private const val SLOW_BELOW = 200_000.0
        private const val FAST_ABOVE = 400_000.0
        /** The least a download must bring to say something of the line: a second in, at its end. */
        private const val MIN_SAMPLE = 32_000L
        private const val MIN_END = 60_000L

        private fun monotonicSeconds(): Double = System.nanoTime() / 1e9
    }
}
