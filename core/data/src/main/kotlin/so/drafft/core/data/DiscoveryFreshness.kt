package so.drafft.core.data

/**
 * When discovery is read again without anyone asking (back at the front, Discover shown again, the
 * account's channel rejoined): only the parts older than that moment allows, so a quick trip to another
 * app or tab reads nothing and a long one reads everything, quietly. Every read of a part counts,
 * whoever asked for it (a live event, the Likes tab, a swipe): the same list is never read twice in a
 * row for nothing.
 *
 * It also keeps reads of one part in order: an answer that lands after a newer one was applied is
 * dropped ([finish]), so a slow, older read never puts back what a newer one removed.
 *
 * Times are seconds on a monotonic clock that keeps counting while the phone sleeps
 * (`SystemClock.elapsedRealtime`): an evening in the background ages what's on screen.
 */
class DiscoveryFreshness {
    enum class Part { DECK, LIKES, MATCHES, LIKES_LEFT }

    /** What asks for the read, and how old (seconds) a part may be at that moment before it's read again. */
    enum class Moment(val maxAge: Double) {
        /** In: signed in, sign-up finished, a hold lifted, a pause ended. Everything, now. */
        ENTERED(0.0),

        /** The account's channel (re)joined: events were maybe missed while it was down. Everything, now. */
        RECONNECTED(0.0),

        /** Back at the front. */
        FOREGROUND(30.0),

        /** The Discover tab shown again. */
        TAB_SHOWN(60.0),
    }

    /** One read of a part, numbered in the order they started. [startedAt]: what it shows is the server as of then. */
    data class Read(val part: Part, val number: Int, val startedAt: Double)

    private var started = 0

    /** When the last applied read of each part started. */
    private val readAt = mutableMapOf<Part, Double>()

    /** The newest read of each part still on its way. */
    private val running = mutableMapOf<Part, Read>()

    /** The number of the last read applied, by part. */
    private val applied = mutableMapOf<Part, Int>()

    /**
     * Whether [part] should be read again at [moment]. A read on its way counts from when it started,
     * unless it looks lost. [empty]: nothing of it shows (no one left to swipe, a failure), where anyone
     * new matters most and the read is light: back at the front or on the tab, it's read whatever its
     * age, unless a read is already on its way.
     */
    fun isDue(part: Part, moment: Moment, now: Double, empty: Boolean = false): Boolean {
        val runningSince = running[part]?.startedAt?.takeIf { now - it < LOST_AFTER }
        if (empty && moment.maxAge > 0) return runningSince == null
        val latest = listOfNotNull(readAt[part], runningSince).maxOrNull() ?: return true
        return now - latest >= moment.maxAge
    }

    /** A read of [part] starts [now]. */
    fun start(part: Part, now: Double): Read {
        started += 1
        return Read(part, started, now).also { running[part] = it }
    }

    /**
     * A read came back, with what the server sent ([ok]) or not. Whether to apply it: never a failed
     * read, nor one older than a read already applied.
     */
    fun finish(read: Read, ok: Boolean): Boolean {
        if (running[read.part] == read) running.remove(read.part)
        if (!ok || read.number <= (applied[read.part] ?: 0)) return false
        applied[read.part] = read.number
        readAt[read.part] = read.startedAt
        return true
    }

    companion object {
        /**
         * A read still running after this long is taken as lost (a request cut short by the background):
         * it no longer counts as fresh, and the next moment reads again.
         */
        const val LOST_AFTER = 20.0
    }
}
