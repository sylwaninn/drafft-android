package so.drafft.core.model

// Ports Drafft/Services/Backend/TermsConsent.swift (the rules; `accept_terms` itself is `ProfileSync.acceptTerms`).

/**
 * The terms and the consent to sensitive data, recorded on the server (`accept_terms`): the version
 * of the terms accepted and when, and when the consent was given. Gender and the genders someone
 * wants to see can reveal their sexual orientation, lifestyle answers their health or beliefs: drafft
 * uses them on explicit consent, which it must be able to show. Sign-up records both on the ground
 * rules step; an onboarded account missing either one for the current version is asked at each open
 * until it accepts (`TermsConsentView`). `complete_onboarding` refuses a sign-up without them
 * (`terms_required`).
 */
object TermsConsent {
    /**
     * The version of the terms on getdrafft.com the app shows, an ISO date (`yyyy-MM-dd`, the shape
     * the server accepts). A newer one asks everyone again.
     */
    const val VERSION = "2026-09-29"

    private val isoDay = Regex("""\d{4}-\d{2}-\d{2}""")

    /**
     * The one version rule: a well-formed version on or after the one this build shows. A newer
     * one (accepted from a newer build) counts, so an older build doesn't ask again in a loop. ISO
     * dates compare as text, which is why a malformed one never counts.
     */
    fun isCurrent(accepted: String?): Boolean = accepted != null && isWellFormed(accepted) && accepted >= VERSION

    /** `yyyy-MM-dd`, digits only: the shape that makes the text comparison a date comparison. */
    fun isWellFormed(version: String): Boolean = isoDay.matches(version)

    /** What the profile holds once `accept_terms` has run: the version and when, and when the consent was given. */
    data class Record(val version: String, val acceptedAt: String, val sensitiveConsentAt: String?) {
        companion object {
            /** Null when the terms were never accepted (no version or no date). */
            fun from(version: String?, acceptedAt: String?, sensitiveConsentAt: String?): Record? =
                if (version == null || acceptedAt == null) null else Record(version, acceptedAt, sensitiveConsentAt)
        }
    }

    /** Whether an account still has to accept: nothing on record, terms older than this version, or no consent to sensitive data. */
    fun isNeeded(record: Record?): Boolean = record == null || !isCurrent(record.version) || record.sensitiveConsentAt == null

    /** Whether the app asks for the consent (`TermsConsentView`). */
    enum class Gate {
        /**
         * Not known yet: no read, a failed one, or a backend without the columns. Nothing is asked
         * meanwhile; a failed read is retried (`AppModel.refreshAccount`).
         */
        UNKNOWN,

        /** A fresh read found it missing. Only a read from the server sets it, never the cache. */
        REQUIRED,

        /**
         * Nothing to ask: on record for this version, or a sign-up still under way (it records the
         * consent itself, and `complete_onboarding` checks it). A cached copy that says so is
         * trusted: the consent is only withdrawn by deleting the account.
         */
        ACCEPTED,
    }

    /**
     * The gate a profile row sets. [carriesConsent]: the row has the three columns `accept_terms`
     * fills; a key that's absent (a backend without them, a copy cached before this build) is told
     * apart from a null one (read, nothing on record).
     */
    fun gate(onboarded: Boolean, carriesConsent: Boolean, record: Record?): Gate = when {
        !onboarded -> Gate.ACCEPTED
        !carriesConsent -> Gate.UNKNOWN
        isNeeded(record) -> Gate.REQUIRED
        else -> Gate.ACCEPTED
    }

    /** What the app does when `accept_terms` fails. */
    sealed interface Failure {
        /**
         * No session, or no profile behind it (an account deleted): nothing this session can
         * record, so it ends, and the welcome screen says the person was logged out.
         */
        data object SignOut : Failure

        data class Message(val text: String) : Failure
    }

    /** [code]: the server's refusal (its `hint`), null without one. [offline]: the request never reached the server. */
    fun failure(code: String?, offline: Boolean, textForCode: (String) -> String?, generic: String): Failure = when {
        code == "unauthenticated" || code == "not_found" -> Failure.SignOut
        // Newer terms are on record (a newer build accepted them): only an update shows them.
        code == "invalid_terms_version" -> Failure.Message(L("drafft has newer terms. Update the app to continue."))
        code != null -> Failure.Message(textForCode(code) ?: generic)
        offline -> Failure.Message(L("Your consent couldn't be saved. Check your connection and try again."))
        // A reply without a code: a server error, or a backend without accept_terms.
        else -> Failure.Message(generic)
    }
}

/**
 * The two boxes of the consent step, unticked until the person ticks them. Sign-up and
 * `TermsConsentView` share them through `ConsentChecks`.
 */
data class ConsentDraft(val terms: Boolean = false, val sensitiveData: Boolean = false) {
    /** Both are required: drafft can't work without the gender. */
    val isComplete: Boolean get() = terms && sensitiveData

    companion object {
        /** Both ticked. */
        val ACCEPTED = ConsentDraft(terms = true, sensitiveData = true)

        /** A resumed sign-up: ticked again only if the version recorded with the consent is current. */
        fun restored(recordedVersion: String?): ConsentDraft = if (TermsConsent.isCurrent(recordedVersion)) ACCEPTED else ConsentDraft()
    }
}
