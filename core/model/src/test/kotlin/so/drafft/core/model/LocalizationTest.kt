package so.drafft.core.model

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalizationTest {
    @AfterTest
    fun reset() = Localization.use(AppLanguage.EN)

    @Test
    fun englishFallsBackToTheKey() {
        Localization.use(AppLanguage.EN)
        assertEquals("Discover", L("Discover"))
    }

    @Test
    fun placeholdersAreFormattedInTheLanguage() {
        Localization.use(AppLanguage.FR)
        assertEquals("3× par semaine", L("%d× a week", 3))
    }

    @Test
    fun androidVariantsReplaceIphoneWording() {
        Localization.use(AppLanguage.EN)
        assertEquals("Couldn't reach Google Play. Try again.", L("Couldn't reach the App Store. Try again."))
        Localization.use(AppLanguage.FR)
        assertEquals("Impossible de joindre Google Play. Réessaie.", L("Couldn't reach the App Store. Try again."))
    }

    @Test
    fun everyAndroidVariantOverridesAKnownKeyInEveryLanguage() {
        val keys = javaClass.getResourceAsStream("/i18n/keys.txt")!!.bufferedReader().readLines().toSet()
        for (language in AppLanguage.entries) {
            val text = javaClass.getResourceAsStream("/i18n/android/${language.code}.json")!!.bufferedReader().readText()
            val variants = kotlinx.serialization.json.Json.decodeFromString<Map<String, String>>(text)
            assertEquals(17, variants.size, language.code)
            variants.keys.forEach { assert(it in keys) { "${language.code}: unknown key $it" } }
            variants.values.forEach { assert(!Regex("iPhone|Apple|App Store").containsMatchIn(it)) { it } }
        }
    }
}
