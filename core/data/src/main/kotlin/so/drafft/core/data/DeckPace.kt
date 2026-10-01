package so.drafft.core.data

import kotlin.math.ceil

// Ports Drafft/Services/DeckPace.swift.

/**
 * When to ask for the next batch of cards: early enough that it arrives, with its first photos, before
 * the person reaches the end at the pace they swipe. Measured rather than guessed: how long the last
 * reads took and how fast the last swipes came, as averages that follow the recent ones. A pause (reading
 * a profile, putting the phone down) doesn't count as a pace.
 */
class DeckPace {
    var readSeconds = 1.5
        private set
    var swipeSeconds = 0.8
        private set
    private var lastSwipe: Double? = null

    /** A deck read that took [took] seconds. */
    fun read(took: Double) {
        readSeconds = readSeconds * 0.5 + maxOf(0.0, took) * 0.5
    }

    /** A swipe at [at] (seconds on a monotonic clock). */
    fun swiped(at: Double) {
        val last = lastSwipe
        if (last != null && at - last < 5) {
            swipeSeconds = swipeSeconds * 0.7 + maxOf(0.15, at - last) * 0.3
        }
        lastSwipe = at
    }

    /**
     * Cards left that ask for the next batch: the swipes a read lasts, plus the photo window; at least
     * [FLOOR], and short of a whole batch (a read must leave something new to swipe).
     */
    val lowWater: Int
        get() {
            val duringRead = ceil(readSeconds / swipeSeconds).toInt()
            return minOf(BATCH - 2, maxOf(FLOOR, duringRead + PHOTO_LEAD))
        }

    companion object {
        /** Cards per read. */
        const val BATCH = 20

        /** Never fewer cards left than this when the next read starts. */
        const val FLOOR = 10

        /**
         * The cards whose photos are fetched ahead of the ones on screen (`PhotoWindow`): a new batch has
         * to be there before the window runs out.
         */
        const val PHOTO_LEAD = 6
    }
}
