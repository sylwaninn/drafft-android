package so.drafft.app.feature.me

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import so.drafft.core.data.platform.Haptics
import so.drafft.core.model.AppLanguage
import so.drafft.core.model.L
import so.drafft.core.ui.LocalAppModel
import so.drafft.core.ui.components.CheckDisc
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.medium

/** You › Language: the same list as at sign-up. Present it in a `DrafftSheet` (medium detent). */
@Composable
fun LanguageSheet(modifier: Modifier = Modifier) {
    val app = LocalAppModel.current
    val scope = rememberCoroutineScope()
    // Drawn first, so the row reacts at once; the whole app then redraws in the new language.
    var selection by remember { mutableStateOf<AppLanguage?>(null) }
    val scroll = rememberScrollState()
    val p = DS.palette

    SheetPage(L("Language"), scroll, modifier) { bars ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(bars)
                .padding(DS.Space.lg),
        ) {
            Column(Modifier.fillMaxWidth().background(p.canvas, RoundedCornerShape(DS.Radius.xl))) {
                AppLanguage.entries.forEachIndexed { i, l ->
                    key(l) {
                        if (i > 0) Hairline(start = DS.Space.lg)
                        val on = (selection ?: app.language) == l
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 52.dp)
                                .semantics { if (on) selected = true }
                                .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) {
                                    Haptics.select()
                                    selection = l
                                    scope.launch {
                                        // After this frame, like `DispatchQueue.main.async`.
                                        withFrameNanos { }
                                        app.language = l
                                    }
                                }
                                .padding(horizontal = DS.Space.lg),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(l.displayName, Modifier.weight(1f), style = TextStyles.body.medium, color = p.ink)
                            CheckDisc(isOn = on)
                        }
                    }
                }
            }
        }
    }
}
