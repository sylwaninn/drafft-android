package so.drafft.core.model

import java.time.Duration
import java.time.Instant

// Ports Drafft/Services/LikeAge.swift.

/**
 * How long ago someone liked you, in the one coarse unit the Likes tiles show ("5 min ago", "2 days
 * ago", "1 year ago"). Computed from the server's real `likedAt`, never invented; a like that has no
 * date shows no label at all.
 *
 * Boundaries (floor in each unit, so the label never overstates): under a minute is "just now" (a
 * clock slightly ahead of the server's lands there too), then minutes up to 59, hours up to 23, days
 * up to 29, months (30 days each) up to 11, then years (365 days each).
 */
sealed interface LikeAge {
    data object Now : LikeAge
    data class Minutes(val n: Int) : LikeAge
    data class Hours(val n: Int) : LikeAge
    data class Days(val n: Int) : LikeAge
    data class Months(val n: Int) : LikeAge
    data class Years(val n: Int) : LikeAge

    /** Lowercase in every language; plurals come from the catalog's plural variations. */
    val text: String
        get() = when (this) {
            Now -> L("just now")
            is Minutes -> L("%d min ago", n)
            is Hours -> L("%d hr ago", n)
            is Days -> L("%d days ago", n)
            is Months -> L("%d months ago", n)
            is Years -> L("%d years ago", n)
        }

    companion object {
        fun of(date: Instant, now: Instant): LikeAge {
            val seconds = Duration.between(date, now).seconds
            return when {
                seconds < 60 -> Now
                seconds < 3_600 -> Minutes((seconds / 60).toInt())
                seconds < 86_400 -> Hours((seconds / 3_600).toInt())
                else -> {
                    val days = (seconds / 86_400).toInt()
                    when {
                        days < 30 -> Days(days)
                        days < 365 -> Months(minOf(days / 30, 11))
                        else -> Years(days / 365)
                    }
                }
            }
        }

        /** The tile's label, or null when the like has no date (nothing is shown rather than a guess). */
        fun text(of: Instant?, now: Instant): String? = of?.let { of(it, now).text }
    }
}

/**
 * Likes are listed newest first, always: a super like doesn't jump the queue. Equal or missing dates
 * keep a stable order (missing ones last, then by id).
 */
fun <T> List<T>.newestFirst(date: (T) -> Instant?, id: (T) -> String): List<T> =
    sortedWith(
        compareByDescending<T, Instant?>(nullsFirst(naturalOrder())) { date(it) }.thenBy { id(it) },
    )
