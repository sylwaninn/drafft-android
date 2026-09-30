package so.drafft.app.feature.profile

// Ports Drafft/Features/Profile/IcebreakerEditor.swift.

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import so.drafft.core.data.platform.Haptics
import so.drafft.core.model.AppLanguage
import so.drafft.core.model.Icebreaker
import so.drafft.core.model.L
import so.drafft.core.model.Localization
import so.drafft.core.model.appLocale
import so.drafft.core.ui.components.pressScale
import so.drafft.core.ui.components.revealsOnFocus
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.NightSurface
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.displayBold
import so.drafft.core.ui.theme.heavy
import so.drafft.core.ui.theme.medium
import so.drafft.core.ui.theme.semibold

/**
 * Edit the interactive prompt in place: the card you edit is the card they'll see.
 * Formats sit in a compact switcher above it; each format edits its own fields inside the card.
 */
@Composable
fun IcebreakerEditor(
    icebreaker: Icebreaker,
    onIcebreakerChange: (Icebreaker) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(DS.Space.md)) {
        FormatSwitcher(icebreaker, onIcebreakerChange)
        EditorCard(icebreaker, onIcebreakerChange)
    }
}

// MARK: Format switcher

/** Format select: a field-like button that opens a menu of the five formats. */
@Composable
private fun FormatSwitcher(icebreaker: Icebreaker, onChange: (Icebreaker) -> Unit) {
    val p = DS.palette
    var open by remember { mutableStateOf(false) }
    val kind = icebreaker.kind
    val shape = RoundedCornerShape(DS.Radius.md)
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 52.dp)
                .background(p.field, shape)
                .border(1.dp, p.ink.copy(alpha = 0.35f), shape)
                .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) { open = true }
                .semantics(mergeDescendants = true) { contentDescription = L("Format, %s", kind.title) }
                .padding(horizontal = DS.Space.lg),
            horizontalArrangement = Arrangement.spacedBy(DS.Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                DrafftIcon(kind.symbol, size = symbol(17f), tint = p.ink)
            }
            Text(kind.title, Modifier.weight(1f), style = TextStyles.body.semibold, color = p.ink)
            DrafftIcon("chevrons-up-down", size = symbol(13f), tint = p.body)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = p.field) {
            Icebreaker.Kind.entries.forEach { k ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(k.title, style = TextStyles.body, color = p.ink)
                            Text(k.detail, style = TextStyles.footnote, color = p.body)
                        }
                    },
                    leadingIcon = { DrafftIcon(k.symbol, tint = p.ink) },
                    trailingIcon = if (k == kind) ({ DrafftIcon("check", tint = p.ink) }) else null,
                    onClick = {
                        open = false
                        if (k != kind) {
                            Haptics.select()
                            onChange(k.blank)
                        }
                    },
                    modifier = Modifier.semantics { selected = k == kind },
                )
            }
        }
    }
}

// MARK: The card

@Composable
private fun EditorCard(icebreaker: Icebreaker, onChange: (Icebreaker) -> Unit) {
    val p = DS.palette
    val shape = RoundedCornerShape(DS.Radius.xl)
    // Editor: plain night fill.
    NightSurface {
        Column(
            Modifier
                .fillMaxWidth()
                .background(p.night, shape)
                .border(1.dp, p.blockEdge, shape)
                .animateContentSize(Motion.snappy())
                .padding(DS.Space.xl),
            verticalArrangement = Arrangement.spacedBy(DS.Space.lg),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(DS.Space.sm), verticalAlignment = Alignment.CenterVertically) {
                    DrafftIcon(icebreaker.kind.symbol, size = symbol(17f), tint = p.accentOnNight)
                    Text(icebreaker.kind.title, style = TextStyles.headline, color = p.accentOnNight)
                }
                Text(icebreaker.kind.detail, style = TextStyles.footnote, color = Color.White.copy(alpha = 0.55f))
            }
            AnimatedContent(
                icebreaker,
                contentKey = { it.kind },
                transitionSpec = { fadeIn(Motion.snappy()).togetherWith(fadeOut(Motion.snappy())) },
                label = "icebreakerFields",
            ) { shown ->
                // Fields always edit the latest value (the animated one only picks the layout).
                Fields(if (shown.kind == icebreaker.kind) icebreaker else shown, onChange)
            }
        }
    }
}

@Composable
private fun Fields(icebreaker: Icebreaker, onChange: (Icebreaker) -> Unit) {
    when (icebreaker) {
        is Icebreaker.TwoTruths -> {
            val statements = icebreaker.statements
            val lie = icebreaker.lieIndex
            val placeholders = listOf(L("I've run 3 marathons"), L("I can do a muscle-up"), L("I've never missed a Sunday run"))
            Column(verticalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
                for (i in 0 until 3) {
                    EditorRow(
                        placeholder = placeholders[i],
                        text = statements.getOrElse(i) { "" },
                        onText = { v -> onChange(Icebreaker.TwoTruths(statements.withIndex(i, v, 3), lie)) },
                        tag = L("Lie"),
                        tagOn = lie == i,
                    ) { onChange(Icebreaker.TwoTruths(statements, i)) }
                }
            }
        }
        is Icebreaker.Joke -> Column(verticalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
            BigField(L("Why did the runner bring a map?"), icebreaker.setup) { onChange(icebreaker.copy(setup = it)) }
            SmallField(L("Punchline, hidden until they tap"), icebreaker.punchline, accent = true) { onChange(icebreaker.copy(punchline = it)) }
        }
        is Icebreaker.HotTake -> BigField(L("Stretching is overrated."), icebreaker.text) { onChange(Icebreaker.HotTake(it)) }
        is Icebreaker.ThisOrThat -> Column(verticalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
            BigField(L("Weekend long run:"), icebreaker.question) { onChange(icebreaker.copy(question = it)) }
            val placeholders = listOf(L("Sunrise"), L("Sunset"))
            Row(horizontalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
                for (i in 0 until 2) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DS.Space.xs), horizontalAlignment = Alignment.CenterHorizontally) {
                        SmallField(placeholders[i], icebreaker.options.getOrElse(i) { "" }, centered = true) { v ->
                            onChange(icebreaker.copy(options = icebreaker.options.withIndex(i, v, 2)))
                        }
                        Tag(L("My pick"), on = icebreaker.pick == i) { onChange(icebreaker.copy(pick = i)) }
                    }
                }
            }
        }
        is Icebreaker.Guess -> Column(verticalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
            BigField(L("My marathon best is…"), icebreaker.question) { onChange(icebreaker.copy(question = it)) }
            val placeholders = listOf("3:15", "3:45", "4:20")
            for (i in 0 until 3) {
                EditorRow(
                    placeholder = placeholders[i],
                    text = icebreaker.options.getOrElse(i) { "" },
                    onText = { v -> onChange(icebreaker.copy(options = icebreaker.options.withIndex(i, v, 3))) },
                    tag = L("Right"),
                    tagOn = icebreaker.answer == i,
                ) { onChange(icebreaker.copy(answer = i)) }
            }
        }
    }
}

/** [this] with slot [i] set to [value], padded to [size] entries. */
private fun List<String>.withIndex(i: Int, value: String, size: Int): List<String> =
    List(maxOf(size, this.size)) { j -> if (j == i) value else getOrElse(j) { "" } }

// MARK: In-card controls (styled like the card they'll see)

/** A text field on night: white text, the accent cursor, a faint placeholder. */
@Composable
private fun NightField(
    text: String,
    onText: (String) -> Unit,
    placeholder: String,
    style: TextStyle,
    maxLines: Int,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = text,
        onValueChange = onText,
        modifier = modifier.revealsOnFocus(),
        textStyle = style,
        maxLines = maxLines,
        cursorBrush = SolidColor(DS.palette.accentOnNight),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        decorationBox = { inner ->
            Box {
                if (text.isEmpty()) Text(placeholder, style = style.copy(color = Color.White.copy(alpha = 0.35f)))
                inner()
            }
        },
    )
}

/** The headline line of a format: large, like the card's display text. */
@Composable
private fun BigField(placeholder: String, text: String, onText: (String) -> Unit) {
    NightField(
        text, onText, placeholder,
        style = displayBold(24f).copy(color = Color.White),
        maxLines = 4,
        modifier = Modifier.fillMaxWidth().padding(vertical = DS.Space.xs),
    )
}

@Composable
private fun SmallField(placeholder: String, text: String, accent: Boolean = false, centered: Boolean = false, onText: (String) -> Unit) {
    NightField(
        text, onText, placeholder,
        style = TextStyles.body.semibold.copy(
            color = if (accent) DS.palette.accentOnNight else Color.White,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        ),
        maxLines = 3,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(DS.Radius.lg))
            .padding(horizontal = DS.Space.md, vertical = 13.dp),
    )
}

@Composable
private fun EditorRow(placeholder: String, text: String, onText: (String) -> Unit, tag: String, tagOn: Boolean, action: () -> Unit) {
    val bg by animateColorAsState(
        if (tagOn) DS.palette.selectedOnNight else Color.White.copy(alpha = 0.08f),
        Motion.snappy(), label = "rowFill",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(DS.Radius.lg))
            .padding(start = DS.Space.md, end = DS.Space.xs, top = DS.Space.xs, bottom = DS.Space.xs),
        horizontalArrangement = Arrangement.spacedBy(DS.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NightField(text, onText, placeholder, style = TextStyles.body.medium.copy(color = Color.White), maxLines = 3, modifier = Modifier.weight(1f))
        Tag(tag, tagOn, action)
    }
}

@Composable
private fun Tag(label: String, on: Boolean, action: () -> Unit) {
    val p = DS.palette
    val bg by animateColorAsState(if (on) p.accentOnNight else Color.White.copy(alpha = 0.08f), Motion.snappy(), label = "tagFill")
    val fg by animateColorAsState(if (on) p.onAccentOnNight else Color.White.copy(alpha = 0.6f), Motion.snappy(), label = "tagInk")
    val spoken = if (Localization.language == AppLanguage.DE) label else label.lowercase(appLocale)
    Box(
        Modifier
            .defaultMinSize(minHeight = 44.dp)
            .pressScale({
                Haptics.select()
                action()
            }, scale = 0.94f)
            .semantics {
                contentDescription = L("Mark as %s", spoken)
                selected = on
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            Modifier
                .background(bg, CircleShape)
                .defaultMinSize(minHeight = 32.dp)
                .padding(horizontal = DS.Space.md, vertical = 8.dp),
            style = TextStyles.caption.heavy,
            color = fg,
            maxLines = 1,
            softWrap = false,
        )
    }
}
