package so.drafft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.display

/**
 * A build that can't reach a backend (a flavor whose config file lacks the Supabase URL or key)
 * stops here and says what's missing. Developer-facing: not translated.
 */
@Composable
fun MissingConfigView(missing: List<String>, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().background(DS.palette.canvasSoft).safeDrawingPadding().padding(DS.Space.xl),
        verticalArrangement = Arrangement.spacedBy(DS.Space.md),
    ) {
        Text("Backend not configured", style = display(28f), color = DS.palette.ink)
        Text(
            "Set these, then build again:",
            style = TextStyles.body, color = DS.palette.body,
        )
        missing.forEach { Text(it, style = TextStyles.body, color = DS.palette.ink) }
    }
}
