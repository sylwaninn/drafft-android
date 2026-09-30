package so.drafft.app.platform

import android.icu.text.DateFormat
import android.icu.util.TimeZone
import android.icu.util.ULocale
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import so.drafft.core.model.DateText

/**
 * Dates in the app's language the way the iPhone writes them: ICU skeletons ("EEEEdMMM", "jmm")
 * resolved per language, like `Date.FormatStyle`. Formatters are cached per skeleton, language and zone.
 */
object IcuDates {
    private val cache = ConcurrentHashMap<String, DateFormat>()

    fun install() {
        DateText.formatter = ::format
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
