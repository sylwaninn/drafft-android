package so.drafft.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Ports DrafftTests/TermsConsentTests.swift (the rules; the column reading is in ProfileSyncTest).

class TermsConsentTest {
    private fun record(version: String? = TermsConsent.VERSION, consent: String? = "2026-09-30T10:00:00Z") =
        TermsConsent.Record.from(version, if (version == null) null else "2026-09-30T10:00:00Z", consent)

    @Test
    fun theShippedVersionIsAnISODate() {
        assertTrue(TermsConsent.isWellFormed(TermsConsent.VERSION))
    }

    @Test
    fun versionRule() {
        assertTrue(TermsConsent.isCurrent(TermsConsent.VERSION))
        assertTrue(TermsConsent.isCurrent("2999-01-01"))
        assertFalse(TermsConsent.isCurrent("2026-09-28"))
        assertFalse(TermsConsent.isCurrent(null))
        assertFalse(TermsConsent.isCurrent("9999"))
        assertFalse(TermsConsent.isCurrent("2026-9-30"))
        assertFalse(TermsConsent.isCurrent("2026-09-30 "))
    }

    @Test
    fun recordNeedsVersionAndDate() {
        assertNull(TermsConsent.Record.from(null, "2026-09-30T10:00:00Z", null))
        assertNull(TermsConsent.Record.from("2026-09-29", null, null))
    }

    @Test
    fun isNeeded() {
        assertTrue(TermsConsent.isNeeded(null))
        assertTrue(TermsConsent.isNeeded(record(version = "2026-09-01")))
        assertTrue(TermsConsent.isNeeded(record(consent = null)))
        assertFalse(TermsConsent.isNeeded(record()))
    }

    @Test
    fun onboardedWithNothingOnRecordIsRequired() {
        assertEquals(TermsConsent.Gate.REQUIRED, TermsConsent.gate(onboarded = true, carriesConsent = true, record = null))
    }

    @Test
    fun currentVersionWithConsentIsAccepted() {
        assertEquals(TermsConsent.Gate.ACCEPTED, TermsConsent.gate(onboarded = true, carriesConsent = true, record = record()))
    }

    @Test
    fun olderVersionIsRequiredAndNewerIsAccepted() {
        assertEquals(TermsConsent.Gate.REQUIRED, TermsConsent.gate(true, true, record(version = "2026-09-01")))
        assertEquals(TermsConsent.Gate.ACCEPTED, TermsConsent.gate(true, true, record(version = "2999-01-01")))
    }

    @Test
    fun termsWithoutSensitiveConsentAreRequired() {
        assertEquals(TermsConsent.Gate.REQUIRED, TermsConsent.gate(true, true, record(consent = null)))
    }

    @Test
    fun notOnboardedIsNeverAsked() {
        assertEquals(TermsConsent.Gate.ACCEPTED, TermsConsent.gate(onboarded = false, carriesConsent = true, record = null))
        assertEquals(TermsConsent.Gate.ACCEPTED, TermsConsent.gate(onboarded = false, carriesConsent = false, record = null))
    }

    @Test
    fun rowWithoutTheColumnsIsUnknown() {
        assertEquals(TermsConsent.Gate.UNKNOWN, TermsConsent.gate(onboarded = true, carriesConsent = false, record = null))
    }

    private fun failure(code: String?, offline: Boolean = false) =
        TermsConsent.failure(code, offline, textForCode = { if (it == "terms_required") "Known words" else null }, generic = "Generic")

    @Test
    fun sessionProblemsSignOut() {
        assertEquals(TermsConsent.Failure.SignOut, failure("unauthenticated"))
        assertEquals(TermsConsent.Failure.SignOut, failure("not_found"))
    }

    @Test
    fun newerTermsOnRecordAskForAnUpdate() {
        assertEquals(TermsConsent.Failure.Message(L("drafft has newer terms. Update the app to continue.")), failure("invalid_terms_version"))
    }

    @Test
    fun knownCodesUseTheirWordsAndUnknownOnesAreGeneric() {
        assertEquals(TermsConsent.Failure.Message("Known words"), failure("terms_required"))
        assertEquals(TermsConsent.Failure.Message("Generic"), failure("something_new"))
    }

    @Test
    fun noCodeSaysConnectionOnlyWhenOffline() {
        assertEquals(TermsConsent.Failure.Message(L("Your consent couldn't be saved. Check your connection and try again.")), failure(null, offline = true))
        assertEquals(TermsConsent.Failure.Message("Generic"), failure(null, offline = false))
    }

    @Test
    fun bothBoxesAreRequired() {
        assertFalse(ConsentDraft().isComplete)
        assertFalse(ConsentDraft(terms = true).isComplete)
        assertFalse(ConsentDraft(sensitiveData = true).isComplete)
        assertTrue(ConsentDraft.ACCEPTED.isComplete)
    }

    @Test
    fun resumeTicksOnlyForTheCurrentVersion() {
        assertEquals(ConsentDraft.ACCEPTED, ConsentDraft.restored(TermsConsent.VERSION))
        assertEquals(ConsentDraft(), ConsentDraft.restored("2026-09-01"))
        assertEquals(ConsentDraft(), ConsentDraft.restored(null))
    }

    @Test
    fun theConsentWordsExistInEveryLanguage() {
        for (language in AppLanguage.entries) {
            Localization.use(language)
            val sentence = L("I agree that drafft uses my %s: my gender, the genders I want to see and my lifestyle, if I fill it in.", L("sensitive data"))
            assertTrue(sentence.contains(L("sensitive data")), "The sensitive data consent lost its link words in ${language.code}")
        }
        Localization.use(AppLanguage.EN)
    }
}
