package so.drafft.app.platform

import android.icu.text.DateFormat
import android.icu.text.RelativeDateTimeFormatter
import android.icu.util.TimeZone
import android.icu.util.ULocale
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import so.drafft.core.model.DateText

/**
 * Dates in the app's language: ICU skeletons ("EEEEdMMM", "jmm")
 * resolved per language. Formatters are cached per skeleton, language and zone.
 */
object IcuDates {
    private val cache = ConcurrentHashMap<String, DateFormat>()

    fun install() {
        DateText.formatter = ::format
        DateText.relativeFormatter = ::relative
    }

    /** "yesterday", "2 weeks ago" in the app's language (named where the language has a word for it). */
    private fun relative(date: Instant, now: Instant, locale: Locale): String {
        val (value, unit) = DateText.relativeUnit(java.time.Duration.between(date, now).seconds)
        val formatter = RelativeDateTimeFormatter.getInstance(ULocale.forLocale(locale))
        val icuUnit = when (unit) {
            "second" -> RelativeDateTimeFormatter.RelativeDateTimeUnit.SECOND
            "minute" -> RelativeDateTimeFormatter.RelativeDateTimeUnit.MINUTE
            "hour" -> RelativeDateTimeFormatter.RelativeDateTimeUnit.HOUR
            "day" -> RelativeDateTimeFormatter.RelativeDateTimeUnit.DAY
            "week" -> RelativeDateTimeFormatter.RelativeDateTimeUnit.WEEK
            "month" -> RelativeDateTimeFormatter.RelativeDateTimeUnit.MONTH
            else -> RelativeDateTimeFormatter.RelativeDateTimeUnit.YEAR
        }
        return synchronized(formatter) { formatter.format(-value.toDouble(), icuUnit) }
    }

    private fun format(skeleton: String, date: Instant, locale: Locale): String {
        val zone = TimeZone.getDefault()
        val key = "$skeleton|${locale.toLanguageTag()}|${zone.id}"
        val formatter = cache.getOrPut(key) {
            DateFormat.getInstanceForSkeleton(skeleton, ULocale.forLocale(locale)).apply { timeZone = zone }
        }
        return synchronized(formatter) { formatter.format(Date.from(date)) }
    }
}
