package so.drafft.app.feature.sessions

import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import so.drafft.app.feature.chat.capitalizedFirst
import so.drafft.core.model.DateText
import so.drafft.core.model.L

/** "Today", "Tomorrow", "In 3 days": how far off a session is, within a week; null beyond. */
val Instant.sessionCountdown: String?
    get() {
        val today = LocalDate.now(DateText.zone)
        val days = ChronoUnit.DAYS.between(today, atZone(DateText.zone).toLocalDate())
        return when (days) {
            0L -> L("Today")
            1L -> L("Tomorrow")
            in 2L..6L -> L("In %d days", days.toInt())
            else -> null
        }
    }

/** "Saturday 12 October", first letter in capitals in every language: a session's day as a title. */
val Instant.sessionDay: String get() = DateText.format("EEEEdMMMM", this).capitalizedFirst
