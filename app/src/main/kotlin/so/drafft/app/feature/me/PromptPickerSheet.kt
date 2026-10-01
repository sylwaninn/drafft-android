package so.drafft.app.feature.me

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import so.drafft.core.data.platform.Haptics
import so.drafft.core.model.L
import so.drafft.core.model.ProfilePrompt
import so.drafft.core.model.PromptCategory
import so.drafft.core.ui.components.CheckDisc
import so.drafft.core.ui.components.DrafftButton
import so.drafft.core.ui.components.EdgeBars
import so.drafft.core.ui.components.GlassCircleButton
import so.drafft.core.ui.components.LocalSheetDismiss
import so.drafft.core.ui.components.PressScaleButton
import so.drafft.core.ui.components.SheetNavBar
import so.drafft.core.ui.components.draftTrail
import so.drafft.core.ui.components.glass
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.bold
import so.drafft.core.ui.theme.medium
import so.drafft.core.ui.theme.semibold

// Port of Drafft/Features/Me/PromptPickerSheet.swift.

/**
 * Searchable, categorised prompt library. Questions already on your profile are left out. Present it
 * in a `DrafftSheet`: picking calls [onPick] and closes it.
 */
@Composable
fun PromptPickerSheet(
    current: String?,
    used: Set<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dismiss = LocalSheetDismiss.current
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    // Tapping a row only selects it; the pinned button confirms. Scrolling never picks anything.
    var selection by rememberSaveable { mutableStateOf(current) }
    val scroll = rememberScrollState()
    val p = DS.palette

    val categories = ProfilePrompt.library.filter { category == null || it.id == category }

    // Questions already on your profile are left out (except the one being changed).
    fun matches(q: String): Boolean =
        (q !in used || q == current) && (query.isEmpty() || ProfilePrompt.text(q).contains(query, ignoreCase = true))

    EdgeBars(
        scroll = scroll,
        modifier = modifier.fillMaxSize().background(p.canvasSoft),
        topBar = {
            Column {
                SheetNavBar(L("Pick a prompt"))
                SearchField(query, { query = it }, L("Search prompts"))
            }
        },
        bottomBar = {
            Column(
                Modifier.padding(start = DS.Space.xl, end = DS.Space.xl, top = DS.Space.md, bottom = DS.Space.sm),
                verticalArrangement = Arrangement.spacedBy(DS.Space.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                DrafftButton(
                    L("Use this prompt"),
                    onClick = {
                        val picked = selection ?: return@DrafftButton
                        Haptics.success()
                        onPick(picked)
                        dismiss()
                    },
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .draftTrail(RoundedCornerShape(DS.Radius.xl), step = DpOffset((-6).dp, 0.dp)),
                    enabled = selection != null && selection != current,
                )
                Text(
                    selection?.let { if (it == current) L("That's your current prompt.") else L("“%s”", ProfilePrompt.text(it)) }
                        ?: L("Tap a prompt to select it."),
                    style = TextStyles.footnote,
                    color = p.body,
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                )
            }
        },
        navigationEdge = true,
        windowInsets = WindowInsets(0, 0, 0, 0),
    ) { bars ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(bars)
                .padding(vertical = DS.Space.sm)
                .animateContentSize(Motion.snappy()),
            verticalArrangement = Arrangement.spacedBy(DS.Space.md),
        ) {
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = DS.Space.lg),
                horizontalArrangement = Arrangement.spacedBy(DS.Space.sm),
            ) {
                CategoryChip(L("All"), "widget", on = category == null) { category = null }
                ProfilePrompt.library.forEach { c ->
                    key(c.id) {
                        CategoryChip(c.name, c.icon, on = category == c.id) { category = c.id }
                    }
                }
            }

            categories.forEach { c ->
                val list = c.questions.filter(::matches)
                if (list.isNotEmpty()) {
                    key(c.id) {
                        CategoryBlock(c, list, selection) { q ->
                            Haptics.select()
                            // No animation: the row switches at once (the disc animates on its own).
                            selection = q
                        }
                    }
                }
            }

            if (categories.all { c -> c.questions.none(::matches) }) {
                // In a white block: no loose text on the sage page.
                Box(
                    Modifier
                        .padding(horizontal = DS.Space.lg)
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 120.dp)
                        .background(p.canvas, RoundedCornerShape(DS.Radius.xl))
                        .padding(DS.Space.lg),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(L("No prompt matches “%s”.", query), style = TextStyles.subheadline, color = p.body, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun CategoryBlock(c: PromptCategory, list: List<String>, selection: String?, onSelect: (String) -> Unit) {
    val p = DS.palette
    Column(
        Modifier
            .padding(horizontal = DS.Space.lg)
            .fillMaxWidth()
            .background(p.canvas, RoundedCornerShape(DS.Radius.xl))
            .padding(start = DS.Space.lg, end = DS.Space.lg, bottom = DS.Space.xs),
    ) {
        IconLabel(
            c.name,
            c.icon,
            Modifier.padding(vertical = DS.Space.md),
            style = TextStyles.footnote.bold,
            color = p.ink,
        )
        list.forEachIndexed { i, q ->
            key(q) {
                PromptRow(q, isSelected = q == selection) { onSelect(q) }
                if (i < list.size - 1) Hairline()
            }
        }
    }
}

@Composable
private fun PromptRow(q: String, isSelected: Boolean, onTap: () -> Unit) {
    val p = DS.palette
    // In the list a question trails off, inviting the answer; on the profile it stands alone.
    val text = "${ProfilePrompt.text(q)}…"
    Row(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { if (isSelected) selected = true }
            // A plain tap (never a drag): a drag always scrolls the list instead of selecting.
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onTap)
            .padding(vertical = DS.Space.md),
        horizontalArrangement = Arrangement.spacedBy(DS.Space.md),
    ) {
        // Laid out at its bold width either way: selecting never re-wraps the line or changes the
        // row's height (a question that needs two lines in bold has two lines unselected too).
        // Never truncated.
        Box(Modifier.weight(1f)) {
            Text(text, Modifier.alpha(0f), style = TextStyles.body.bold)
            Text(text, style = TextStyles.body.medium, color = p.ink)
        }
        // Centred on the first line, whatever the state.
        CheckDisc(isOn = isSelected)
    }
}

@Composable
private fun CategoryChip(title: String, icon: String, on: Boolean, onClick: () -> Unit) {
    val p = DS.palette
    PressScaleButton(
        onClick = {
            Haptics.select()
            onClick()
        },
        modifier = Modifier
            .defaultMinSize(minHeight = 44.dp)
            .semantics { if (on) selected = true },
        scale = 0.94f,
    ) {
        IconLabel(
            title,
            icon,
            Modifier
                .defaultMinSize(minHeight = 36.dp)
                .background(if (on) p.lime else p.canvas, CircleShape)
                .padding(horizontal = DS.Space.md),
            style = TextStyles.footnote.semibold,
            color = if (on) p.onLime else p.ink,
            maxLines = 1,
        )
    }
}

/**
 * `.searchable(placement: .navigationBarDrawer(displayMode: .always))`: the search field always
 * shown under the sheet's title, in glass (so it reads over the list scrolling under it).
 */
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, prompt: String) {
    val p = DS.palette
    Row(
        Modifier
            .padding(start = DS.Space.lg, end = DS.Space.lg, bottom = DS.Space.sm)
            .fillMaxWidth()
            .height(36.dp)
            .glass(RoundedCornerShape(18.dp))
            .padding(start = DS.Space.md),
        horizontalArrangement = Arrangement.spacedBy(DS.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DrafftIcon("magnifier", size = 18.dp, tint = p.body)
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            textStyle = TextStyles.body.copy(color = p.ink),
            singleLine = true,
            cursorBrush = SolidColor(p.accentInk),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text(prompt, style = TextStyles.body, color = p.mute, maxLines = 1)
                    inner()
                }
            },
        )
        if (query.isNotEmpty()) {
            GlassCircleButton(
                "close-circle",
                onClick = { onQueryChange("") },
                size = 36.dp,
                contentDescription = L("Clear search"),
                glyph = p.mute,
            )
        }
    }
}
