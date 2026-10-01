package so.drafft.core.data.media

// Ports Drafft/Services/Media/NetworkQuality.swift. The path (metered, Data Saver) comes from the
// Android side (`installImages` in core:ui), the speed from the photo downloads themselves.

/**
 * Whether the connection is limited, for how much the app fetches ahead and how big (`ImageStore`,
 * `PhotoWindow`). Limited: a metered network (cellular), Data Saver, or photos measured arriving slowly
 * (under about 1.6 Mbit/s each, back to normal above 3.2: a gap so one slow photo doesn't flip it back
 * and forth). The speed is an average that follows the last few downloads ([download]).
 */
class NetworkQuality {
    private val lock = Any()
    private var expensive = false
    private var constrained = false

    /** Bytes per second, averaged over recent photo downloads. */
    private var speed: Double? = null
    private var slow = false
    private var limitedNow = false
    private val observers = mutableListOf<(Boolean) -> Unit>()

    val isLimited: Boolean get() = synchronized(lock) { limitedNow }

    /** Called now with the current state, then on every change (any thread). */
    fun onChange(observer: (Boolean) -> Unit) {
        val now = synchronized(lock) {
            observers += observer
            limitedNow
        }
        observer(now)
    }

    /** The network in use: [expensive] (metered, cellular), [constrained] (Data Saver). */
    fun path(expensive: Boolean, constrained: Boolean) {
        synchronized(lock) {
            this.expensive = expensive
            this.constrained = constrained
        }
        changed()
    }

    /**
     * Times a photo download started at [now] (seconds on a monotonic clock): once, a second in (a slow
     * line shows within the first download, finished or not), or at its end for one shorter than that and
     * of some size (a small file is all latency, which says nothing of the line).
     */
    fun download(now: Double = monotonicSeconds()): Download = Download(now)

    /** One photo download's bytes so far, and whether its speed was recorded (once). */
    inner class Download internal constructor(private val start: Double) {
        private var total = 0L
        private var recorded = false

        /** [bytes] more arrived at [now]. */
        fun received(bytes: Long, now: Double = monotonicSeconds()) {
            val total = synchronized(this) { total += bytes; total }
            val elapsed = now - start
            if (elapsed >= 1 && claim()) record(total / elapsed)
        }

        /** The download ended at [now]: read to its end, or [failed] (an error, or cancelled). */
        fun ended(failed: Boolean, now: Double = monotonicSeconds()) {
            val total = synchronized(this) { total }
            val elapsed = now - start
            if ((elapsed >= 1 || (!failed && total >= MIN_BYTES)) && elapsed > 0 && claim()) record(total / elapsed)
        }

        /** True the first time only. */
        private fun claim(): Boolean = synchronized(this) {
            if (recorded) return false
            recorded = true
            true
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

    private fun changed() {
        val (limited, notify) = synchronized(lock) {
            val limited = expensive || constrained || slow
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
        private const val MIN_BYTES = 60_000L

        private fun monotonicSeconds(): Double = System.nanoTime() / 1e9
    }
}
