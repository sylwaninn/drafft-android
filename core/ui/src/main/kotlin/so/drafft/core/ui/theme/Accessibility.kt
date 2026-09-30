package so.drafft.core.ui.theme

import androidx.compose.runtime.compositionLocalOf

/**
 * The system's "Remove animations" setting (iOS: Reduce Motion), provided at the root by the app.
 * Components that move things across the screen (banners, covers, the Discover empty state) read it
 * and fade instead, or show the end state at once.
 */
val LocalReduceMotion = compositionLocalOf { false }
