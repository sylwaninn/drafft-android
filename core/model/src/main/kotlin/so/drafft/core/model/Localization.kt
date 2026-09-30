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

    private fun load(language: AppLanguage): Map<String, String> = cache.getOrPut(language) {
        val stream = Localization::class.java.getResourceAsStream("/i18n/${language.code}.json")
            ?: return@getOrPut emptyMap()
        stream.bufferedReader(Charsets.UTF_8).use { json.decodeFromString<Map<String, String>>(it.readText()) }
    }

    fun string(key: String, args: Array<out Any?>): String {
        val language = this.language
        val template = table[key] ?: (if (language != AppLanguage.EN) load(AppLanguage.EN)[key] else null) ?: key
        if (args.isEmpty() && !template.contains("%%")) return template
        return runCatching { String.format(language.locale, template, *args) }.getOrDefault(template)
    }
}

/** A string from the catalog in the app's language. The key is the English source text. */
fun L(key: String, vararg args: Any?): String = Localization.string(key, args)

/** The app's language, for dates and numbers formatted in code. */
val appLocale: Locale get() = Localization.language.locale
