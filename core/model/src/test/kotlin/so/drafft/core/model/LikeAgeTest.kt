package so.drafft.core.model

import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LikeAgeTest {
    private val now = Instant.ofEpochSecond(1_800_000_000)

    @AfterTest
    fun reset() = Localization.use(AppLanguage.EN)

    private fun age(seconds: Long) = LikeAge.of(now.minusSeconds(seconds), now)

    @Test
    fun underAMinuteIsJustNow() {
        assertEquals(LikeAge.Now, age(0))
        assertEquals(LikeAge.Now, age(59))
        // A clock a little ahead of the server's.
        assertEquals(LikeAge.Now, age(-30))
    }

    @Test
    fun minutesThenHours() {
        assertEquals(LikeAge.Minutes(1), age(60))
        assertEquals(LikeAge.Minutes(59), age(59 * 60 + 59))
        assertEquals(LikeAge.Hours(1), age(3_600))
        assertEquals(LikeAge.Hours(23), age(23 * 3_600 + 3_599))
    }

    @Test
    fun daysThenMonths() {
        assertEquals(LikeAge.Days(1), age(86_400))
        assertEquals(LikeAge.Days(29), age(29 * 86_400 + 86_399))
        assertEquals(LikeAge.Months(1), age(30 * 86_400))
        assertEquals(LikeAge.Months(1), age(59 * 86_400))
        assertEquals(LikeAge.Months(2), age(60 * 86_400))
        assertEquals(LikeAge.Months(11), age(364 * 86_400))
    }

    @Test
    fun years() {
        assertEquals(LikeAge.Years(1), age(365 * 86_400))
        assertEquals(LikeAge.Years(1), age(729 * 86_400))
        assertEquals(LikeAge.Years(2), age(730 * 86_400))
    }

    @Test
    fun noDateNoLabel() {
        assertNull(LikeAge.text(null, now))
    }

    @Test
    fun newestFirstWhateverTheKind() {
        data class Row(val id: String, val at: Instant?)
        val rows = listOf(
            Row("old", now.minusSeconds(300)), Row("none", null), Row("new", now.minusSeconds(10)),
            Row("b", now.minusSeconds(100)), Row("a", now.minusSeconds(100)),
        )
        assertEquals(listOf("new", "a", "b", "old", "none"), rows.newestFirst({ it.at }, { it.id }).map { it.id })
    }

    @Test
    fun englishPlurals() {
        assertEquals("1 year ago", LikeAge.Years(1).text)
        assertEquals("2 years ago", LikeAge.Years(2).text)
        assertEquals("1 month ago", LikeAge.Months(1).text)
        assertEquals("5 min ago", LikeAge.Minutes(5).text)
        assertEquals("just now", LikeAge.Now.text)
    }

    @Test
    fun everyLanguageSaysItLowercaseWithItsOwnPlural() {
        val expected = mapOf(
            AppLanguage.FR to listOf("à l'instant", "il y a 5 min", "il y a 3 h", "il y a 2 j", "il y a 1 mois", "il y a 3 mois", "il y a 1 an", "il y a 2 ans"),
            AppLanguage.ES to listOf("ahora mismo", "hace 5 min", "hace 3 h", "hace 1 día", "hace 1 mes", "hace 3 meses", "hace 1 año", "hace 2 años"),
            AppLanguage.DE to listOf("gerade eben", "vor 5 min", "vor 3 h", "vor 1 tag", "vor 1 monat", "vor 3 monaten", "vor 1 jahr", "vor 2 jahren"),
            AppLanguage.IT to listOf("adesso", "5 min fa", "3 h fa", "1 giorno fa", "1 mese fa", "3 mesi fa", "1 anno fa", "2 anni fa"),
            AppLanguage.PT to listOf("agora mesmo", "há 5 min", "há 3 h", "há 1 dia", "há 1 mês", "há 3 meses", "há 1 ano", "há 2 anos"),
            AppLanguage.NL to listOf("zojuist", "5 min geleden", "3 uur geleden", "1 dag geleden", "1 mnd geleden", "3 mnd geleden", "1 jaar geleden", "2 jaar geleden"),
        )
        for ((language, texts) in expected) {
            Localization.use(language)
            val actual = listOf(
                LikeAge.Now, LikeAge.Minutes(5), LikeAge.Hours(3),
                if (language == AppLanguage.FR) LikeAge.Days(2) else LikeAge.Days(1),
                LikeAge.Months(1), LikeAge.Months(3), LikeAge.Years(1), LikeAge.Years(2),
            ).map { it.text }
            assertEquals(texts, actual, language.code)
            actual.forEach { assertEquals(it.lowercase(), it, language.code) }
        }
    }
}
