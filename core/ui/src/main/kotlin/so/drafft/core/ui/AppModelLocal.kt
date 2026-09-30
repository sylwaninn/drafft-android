package so.drafft.core.ui

import androidx.compose.runtime.staticCompositionLocalOf
import so.drafft.core.data.AppModel

/** The app's state for every screen, like SwiftUI's `@Environment(AppModel.self)`. Provided at the root. */
val LocalAppModel = staticCompositionLocalOf<AppModel> { error("AppModel is provided at the root") }
