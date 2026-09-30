package so.drafft.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import so.drafft.core.data.platform.Haptics
import so.drafft.core.model.L
import so.drafft.core.model.Sport
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.semibold

// Port of Drafft/DesignSystem/SportPicker.swift.

/**
 * The one sport picker: a search field over the full catalog, then chips in a fixed order.
 * A [limit] greys out the rest once reached.
 */
@Composable
fun SportPicker(
    selected: List<Sport>,
    onToggle: (Sport) -> Unit,
    modifier: Modifier = Modifier,
    limit: Int? = null,
    chipBackground: Color = DS.palette.canvas,
    /**
     * When set, only the first sports of the catalog (plus any picked one) show until "All sports"
     * is tapped or a search is typed. Keeps a sheet light to open and short to scan.
     */
    collapsedCount: Int? = null,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var searchFocused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val p = DS.palette
    val catalog = Sport.entries
    val isCollapsed = collapsedCount != null && !expanded && query.isBlank()
    // Catalog order, always: picking a sport never moves the chips around.
    val results = if (isCollapsed) {
        catalog.filterIndexed { i, s -> i < collapsedCount!! || s in selected }
    } else {
        catalog.filter { it.matches(query) }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(DS.Space.md)) {
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 44.dp)
                .background(p.ink.copy(alpha = 0.06f), CircleShape)
                // The whole capsule focuses the field, icon and padding included.
                .clickable(remember { MutableInteractionSource() }, indication = null) { focus.requestFocus() }
                .padding(start = DS.Space.md, end = if (query.isEmpty()) DS.Space.md else 0.dp)
                // Inside a FocusScrollView (sign-up, Edit profile, Filters) it scrolls clear of the keyboard.
                .revealsOnFocus(searchFocused),
            horizontalArrangement = Arrangement.spacedBy(DS.Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DrafftIcon("magnifier", tint = p.body)
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focus)
                    .onFocusChanged { searchFocused = it.isFocused },
                textStyle = TextStyles.body.copy(color = p.ink),
                singleLine = true,
                cursorBrush = SolidColor(p.accentInk),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                decorationBox = { inner ->
                    Box(Modifier.defaultMinSize(minHeight = 44.dp), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(L("Search %d sports", catalog.size), style = TextStyles.body, color = p.mute, maxLines = 1)
                        }
                        inner()
                    }
                },
            )
            if (query.isNotEmpty()) {
                PressScaleButton(
                    onClick = { query = "" },
                    modifier = Modifier.size(44.dp),
                    scale = 1f,
                    contentDescription = L("Clear search"),
                ) {
                    DrafftIcon("close-circle", tint = p.mute)
                }
            }
        }

        if (results.isEmpty()) {
            Text(L("No sport called “%s”. Try another word.", query), style = TextStyles.footnote, color = p.body)
        } else {
            val full = limit?.let { selected.size >= it } ?: false
            FlowLayout(spacing = DS.Space.sm) {
                results.forEach { s ->
                    val on = s in selected
                    val blocked = !on && full
                    PressScaleButton(
                        onClick = {
                            Haptics.select()
                            // A colour change, not a layout change: quick ease, no spring (SportChip).
                            onToggle(s)
                        },
                        modifier = Modifier
                            .defaultMinSize(minHeight = 44.dp)
                            .alpha(if (blocked) 0.4f else 1f)
                            .selectedTrait(on),
                        scale = 0.95f,
                        enabled = !blocked,
                    ) {
                        SportChip(s, selected = on, fill = chipBackground)
                    }
                }
                if (isCollapsed) {
                    PressScaleButton(
                        onClick = {
                            Haptics.tap()
                            expanded = true
                        },
                        modifier = Modifier.defaultMinSize(minHeight = 44.dp),
                        scale = 0.95f,
                    ) {
                        Row(
                            Modifier.padding(horizontal = DS.Space.md, vertical = DS.Space.sm),
                            horizontalArrangement = Arrangement.spacedBy(DS.Space.xs + 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(L("All %d sports", catalog.size), style = TextStyles.subheadline.semibold, color = p.accentInk)
                            DrafftIcon("alt-arrow-down", size = symbolBox(12f), tint = p.accentInk)
                        }
                    }
                }
            }
        }
    }
}
