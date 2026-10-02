package so.drafft.core.data.verification

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Test

// The phone step reads the country from the number itself
// when it's typed the international way, whatever country was picked, and leaves a national number to
// the picked country.
class PhoneCountryTest {
    private fun country(region: String) = assertNotNull(PhoneCountry.country(region))

    @Test
    fun plusCodeNamesTheCountry() {
        val (found, national) = assertNotNull(PhoneCountry.international("+33 6 12 34 56 78", country("US")))
        assertEquals("FR", found.region)
        assertEquals("0612345678", national.filter(Char::isDigit))
    }

    @Test
    fun exitCodeOfThePickedCountry() {
        assertEquals("GB", PhoneCountry.international("0044 7700 900123", country("FR"))?.first?.region)
        assertEquals("GB", PhoneCountry.international("011 44 7700 900123", country("US"))?.first?.region)
    }

    @Test
    fun codeAloneWhileTyping() {
        val (found, national) = assertNotNull(PhoneCountry.international("+351", country("FR")))
        assertEquals("PT", found.region)
        assertEquals("", national)
        // Not a code yet: nothing changes.
        assertNull(PhoneCountry.international("+3", country("FR")))
    }

    @Test
    fun sharedCodeKeepsThePickedCountry() {
        assertEquals("CA", PhoneCountry.international("+1", country("CA"))?.first?.region)
        assertEquals("US", PhoneCountry.international("+1", country("FR"))?.first?.region)
    }

    @Test
    fun nationalNumbersStayWithThePickedCountry() {
        assertNull(PhoneCountry.international("06 12 34 56 78", country("FR")))
        assertNull(PhoneCountry.international("6 12 34 56 78", country("FR")))
        assertNull(PhoneCountry.international("(415) 555-0132", country("US")))
        assertNotNull(PhoneCountry.mobileNumber("0612345678", country("FR")))
        assertNotNull(PhoneCountry.mobileNumber("612345678", country("FR")))
    }
}
