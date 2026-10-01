package so.drafft.core.model

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json

/** App languages offered at sign-up and in You. Defaults to the phone's language when we have it, English otherwise. */
enum class AppLanguage(val code: String, val displayName: String) {
    EN("en", "English"),
    FR("fr", "Français"),
    ES("es", "Español"),
    DE("de", "Deutsch"),
    IT("it", "Italiano"),
    PT("pt", "Português"),
    NL("nl", "Nederlands");

    /** The language's folder on getdrafft.com, English at the root. */
    val sitePath: String get() = if (this == EN) "" else "/$code"

    /** Portuguese is European Portuguese (see NotificationText). */
    val locale: Locale get() = if (this == PT) Locale.forLanguageTag("pt-PT") else Locale.forLanguageTag(code)

    companion object {
        fun fromCode(code: String?): AppLanguage? = entries.firstOrNull { it.code == code?.lowercase() }

        /** The first of the phone's preferred languages the app speaks, English otherwise. */
        fun deviceDefault(preferred: List<Locale> = listOf(Locale.getDefault())): AppLanguage =
            preferred.firstNotNullOfOrNull { fromCode(it.language) } ?: EN
    }
}

/**
 * The interface language, picked in the app rather than the phone's. Strings come from the same
 * catalog as the iPhone app (scripts/sync-strings.py): the key is the English text, placeholders are
 * Java's (`%s`, `%d`, `%1$s`). The root of the UI is keyed on [language], so every screen redraws in
 * the new language at once.
 */
object Localization {
    @Volatile
    private var current: AppLanguage = AppLanguage.EN

    /** The language in use. Reading it (directly, through [L] or [appLocale]) is observed by the UI. */
    val language: AppLanguage
        get() {
            observer.read()
            return current
        }

    /**
     * Lets the UI observe the language like SwiftUI observes `Localization.shared`: every text that
     * called [L] redraws when it changes, nothing else. Installed by core:ui (a snapshot state).
     */
    interface Observer {
        fun read()
        fun changed(language: AppLanguage)
    }

    @Volatile
    var observer: Observer = object : Observer {
        override fun read() = Unit
        override fun changed(language: AppLanguage) = Unit
    }

    @Volatile
    private var table: Map<String, String> = emptyMap()
    private val cache = ConcurrentHashMap<AppLanguage, Map<String, String>>()
    private val json = Json { ignoreUnknownKeys = true }

    /** Switches the language (loads its table once; call early, off the main thread when possible). */
    fun use(language: AppLanguage) {
        table = load(language)
        current = language
        observer.changed(language)
    }

    /** Loads a language's table ahead of time (the one picked last, at launch). */
    fun preload(language: AppLanguage) {
        load(language)
    }

    /**
     * The shared catalog's table for [language], with the Android variants on top: the few sentences
     * the iPhone writes for its own platform (App Store, Apple Account, iPhone Settings) have an
     * Android wording under the same key in `i18n/android/` (Google Play, Google account, the phone's
     * settings). English included, since the catalog's English is the key itself.
     */
    private fun load(language: AppLanguage): Map<String, String> = cache.getOrPut(language) {
        read("/i18n/${language.code}.json") + read("/i18n/android/${language.code}.json")
    }

    private fun read(path: String): Map<String, String> {
        val stream = Localization::class.java.getResourceAsStream(path) ?: return emptyMap()
        return stream.bufferedReader(Charsets.UTF_8).use { json.decodeFromString<Map<String, String>>(it.readText()) }
    }

    /** The plural form of a count for [language] (CLDR, for the numbers drafft shows): "one" or "other". */
    fun pluralCategory(language: AppLanguage, count: Long): String = when (language) {
        // French and Portuguese: 0 and 1 are singular.
        AppLanguage.FR, AppLanguage.PT -> if (count == 0L || count == 1L) "one" else "other"
        else -> if (count == 1L) "one" else "other"
    }

    fun string(key: String, args: Array<out Any?>): String {
        val language = this.language
        // A string with plural variations keeps each form under `key|one`...: the first argument is the count.
        val count = (args.firstOrNull() as? Number)?.toLong()
        val form = count?.let { "$key|${pluralCategory(language, it)}" }
        // Only this language's own forms: a language without a "one" form (the same word either way)
        // must not fall back to English's.
        val template = form?.let { table[it] }
            ?: table[key] ?: (if (language != AppLanguage.EN) load(AppLanguage.EN)[key] else null) ?: key
        if (args.isEmpty() && !template.contains("%%")) return template
        return runCatching { String.format(language.locale, template, *args) }.getOrDefault(template)
    }
}

/** A string from the catalog in the app's language. The key is the English source text. */
fun L(key: String, vararg args: Any?): String = Localization.string(key, args)

/** The app's language, for dates and numbers formatted in code. */
val appLocale: Locale get() = Localization.language.locale
