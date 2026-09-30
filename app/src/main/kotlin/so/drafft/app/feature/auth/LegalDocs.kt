package so.drafft.app.feature.auth

import so.drafft.core.model.AppLanguage
import so.drafft.core.model.L
import so.drafft.core.model.Localization

// Port of Drafft/Features/Auth/LegalDocs.swift.

/**
 * The legal documents. Their only text is the one published on getdrafft.com, opened in the
 * browser: the app never keeps a copy that could say something else.
 */
enum class LegalDoc(val rawValue: String) {
    TERMS("terms"), PRIVACY("privacy"), COMMUNITY("community");

    val id: LegalDoc get() = this

    val title: String
        get() = when (this) {
            TERMS -> L("Terms of Use")
            PRIVACY -> L("Privacy Policy")
            COMMUNITY -> L("Community Guidelines")
        }

    /** The page in [language], the app's by default. The community guidelines are a section of the terms. */
    fun url(language: AppLanguage = Localization.language): String = when (this) {
        TERMS -> page("terms", language = language)
        PRIVACY -> page("privacy", language = language)
        COMMUNITY -> page("terms", section = "community", language = language)
    }

    companion object {
        fun fromRaw(raw: String?): LegalDoc? = entries.firstOrNull { it.rawValue == raw }

        /** The privacy policy's section on sensitive data, linked from the consent to it. */
        fun sensitiveData(language: AppLanguage = Localization.language): String =
            page("privacy", section = "sensitive-data", language = language)

        /**
         * getdrafft.com/<language>/<page>?lang=<code>#<section>, English at the root. The pages switch
         * to the browser's language unless `lang` names one, and the browser reports the phone's
         * languages, not the app's: `lang` keeps the page in the app's language. Section anchors are
         * the same in every language.
         */
        private fun page(page: String, section: String? = null, language: AppLanguage): String =
            "https://getdrafft.com${language.sitePath}/$page?lang=${language.code}" + (section?.let { "#$it" } ?: "")
    }
}
