package so.drafft.core.ui.theme

import androidx.compose.runtime.mutableStateOf
import so.drafft.core.model.AppLanguage
import so.drafft.core.model.Localization

/**
 * Backs [Localization]'s language with a snapshot state, so a composable that called `L(...)` (or
 * formatted a date in the app's language) is recomposed when the language changes, and nothing
 * else: sign-up keeps its step, the tabs keep their place. Installed once at launch.
 */
object LanguageObservation {
    private val state = mutableStateOf(Localization.language)

    fun install() {
        state.value = Localization.language
        Localization.observer = object : Localization.Observer {
            override fun read() {
                state.value
            }

            override fun changed(language: AppLanguage) {
                state.value = language
            }
        }
    }
}
