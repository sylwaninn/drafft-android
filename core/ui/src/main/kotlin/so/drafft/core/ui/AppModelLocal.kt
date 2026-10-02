package so.drafft.core.ui

import androidx.compose.runtime.staticCompositionLocalOf
import so.drafft.core.data.AppModel

/** The app's state for every screen. Provided at the root. */
val LocalAppModel = staticCompositionLocalOf<AppModel> { error("AppModel is provided at the root") }
