package so.drafft.core.data.backend

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.Test
import so.drafft.core.model.TermsConsent

// Terms consent: reading the gate from the profile row's bytes.
class AccountConsentTest {
    private fun row(onboarded: Boolean = true, consent: String = ""): ByteArray = """
        [{"name": "Maya", "birthdate": "1995-04-12", "neighborhood": "Belleville", "bio": "", "goal": "", "favorite_spot": "",
          "drinks": "", "smokes": "", "diet": "", "chronotype": "", "paused": false,
          "onboarded_at": ${if (onboarded) "\"2026-09-01T10:00:00Z\"" else "null"}$consent}]
    """.trimIndent().toByteArray()

    private fun gate(data: ByteArray): TermsConsent.Gate {
        val account = ProfileSync.decodeAccount(data, mediaBase = null)
        assertNotNull(account)
        return account.consent
    }

    @Test
    fun onboardedWithNullColumnsIsRequired() {
        val columns = """, "terms_version": null, "terms_accepted_at": null, "sensitive_consent_at": null"""
        assertEquals(TermsConsent.Gate.REQUIRED, gate(row(consent = columns)))
    }

    @Test
    fun currentVersionWithConsentIsAccepted() {
        val columns = """, "terms_version": "${TermsConsent.VERSION}", "terms_accepted_at": "2026-09-30T10:00:00Z", "sensitive_consent_at": "2026-09-30T10:00:00Z""""
        assertEquals(TermsConsent.Gate.ACCEPTED, gate(row(consent = columns)))
    }

    @Test
    fun termsWithoutTheConsentAreRequired() {
        val columns = """, "terms_version": "${TermsConsent.VERSION}", "terms_accepted_at": "2026-09-30T10:00:00Z", "sensitive_consent_at": null"""
        assertEquals(TermsConsent.Gate.REQUIRED, gate(row(consent = columns)))
    }

    @Test
    fun rowWithoutTheColumnsIsUnknown() {
        assertEquals(TermsConsent.Gate.UNKNOWN, gate(row()))
    }

    @Test
    fun signUpUnderWayIsNeverAsked() {
        assertEquals(TermsConsent.Gate.ACCEPTED, gate(row(onboarded = false)))
    }
}
