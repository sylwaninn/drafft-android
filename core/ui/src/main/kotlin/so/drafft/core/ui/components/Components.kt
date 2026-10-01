package so.drafft.core.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import so.drafft.core.data.media.Images
import so.drafft.core.data.media.NetworkQuality
import so.drafft.core.model.L
import so.drafft.core.model.Sport
import so.drafft.core.ui.image.BundledImages
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.LocalDarkTheme
import so.drafft.core.ui.theme.LocalIsNightSurface
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.heavy
import so.drafft.core.ui.theme.medium
import so.drafft.core.ui.theme.monospacedDigits
import so.drafft.core.ui.theme.semibold
import java.text.BreakIterator
import androidx.compose.ui.res.painterResource

// Port of Drafft/DesignSystem/Components.swift.

/**
 * The box an SF Symbol set at [fontSize] points takes: Material glyphs sit in a square with a small
 * margin, so the box is a bit larger than the type size to draw the glyph at the same visual size.
 */
internal fun symbolBox(fontSize: Float): Dp = (fontSize * 1.2f).dp

/** A size in dp as a font size that ignores the font scale, like `.system(size:)` on iOS. */
@Composable
internal fun Dp.fixedSp(): TextUnit = with(LocalDensity.current) { this@fixedSp.toSp() }

// MARK: - Buttons

enum class DrafftButtonKind { PRIMARY, SECONDARY, TERTIARY, DARK, LIKE }

/**
 * `DrafftButtonStyle`: `.drafftPrimary` is `kind = PRIMARY`, `.drafftPrimaryFit` is
 * `fullWidth = false` (a single action in the middle of a page: empty states), `.drafftSecondary`,
 * `.drafftTertiary`, `.drafftDark`. On a night surface the primary turns `accentOnNight` by itself.
 * The whole rounded frame takes the touch.
 */
@Composable
fun DrafftButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: DrafftButtonKind = DrafftButtonKind.PRIMARY,
    fullWidth: Boolean = true,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val onNight = LocalIsNightSurface.current
    val p = DS.palette
    val foreground = when (kind) {
        DrafftButtonKind.PRIMARY -> if (onNight) p.onAccentOnNight else p.onLime
        DrafftButtonKind.LIKE -> p.onLike
        DrafftButtonKind.SECONDARY, DrafftButtonKind.TERTIARY -> p.ink
        DrafftButtonKind.DARK -> p.accentOnNight
    }
    val background = when (kind) {
        DrafftButtonKind.PRIMARY -> if (onNight) {
            if (pressed) p.accentOnNightActive else p.accentOnNight
        } else {
            if (pressed) p.limeActive else p.lime
        }
        DrafftButtonKind.LIKE -> if (pressed) p.likeActive else p.like
        DrafftButtonKind.SECONDARY -> p.canvasSoft.copy(alpha = if (pressed) 0.7f else 1f)
        DrafftButtonKind.TERTIARY -> p.canvas
        DrafftButtonKind.DARK -> if (pressed) p.nightRaised else p.night
    }
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, Motion.snappy(), label = "press")
    val shape = RoundedCornerShape(DS.Radius.xl)
    Row(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.45f
            }
            .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
            .background(background, shape)
            .then(if (kind == DrafftButtonKind.TERTIARY) Modifier.border(1.dp, p.ink, shape) else Modifier)
            .clip(shape)
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = 52.dp)
            .padding(horizontal = DS.Space.xl, vertical = DS.Space.xs),
        horizontalArrangement = Arrangement.spacedBy(DS.Space.sm, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides foreground,
            LocalTextStyle provides TextStyles.body.semibold.copy(textAlign = TextAlign.Center),
        ) { content() }
    }
}

/** [DrafftButton] with a text label. A long translation takes a second, centred line (the button grows), never "…". */
@Composable
fun DrafftButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: DrafftButtonKind = DrafftButtonKind.PRIMARY,
    fullWidth: Boolean = true,
    enabled: Boolean = true,
) {
    DrafftButton(onClick, modifier, kind, fullWidth, enabled) {
        Text(text, maxLines = 2, overflow = TextOverflow.Clip)
    }
}

/**
 * `PressScaleStyle`: shrinks while pressed, no ripple. The whole laid-out frame takes the touch.
 * Without that only drawn pixels do: a glyph in a 68 pt frame, or a glass circle, answered only when
 * the finger landed on the symbol itself.
 */
@Composable
fun Modifier.pressScale(
    onClick: () -> Unit,
    scale: Float = 0.92f,
    enabled: Boolean = true,
    role: Role? = Role.Button,
    onClickLabel: String? = null,
    interactionSource: MutableInteractionSource? = null,
): Modifier {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) scale else 1f, Motion.snappy(), label = "pressScale")
    return this
        .graphicsLayer {
            scaleX = s
            scaleY = s
        }
        .clickable(source, indication = null, enabled = enabled, onClickLabel = onClickLabel, role = role, onClick = onClick)
}

/** A button drawn by its [content] with `PressScaleStyle`. */
@Composable
fun PressScaleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    scale: Float = 0.92f,
    enabled: Boolean = true,
    contentDescription: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    // Scaled as a whole: the backgrounds and frames given in [modifier] shrink with the press.
    Box(
        Modifier
            .pressScale(onClick, scale, enabled)
            .then(modifier)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/**
 * `TextLinkStyle`, for text actions ("Clear filters", "Forgot?", "Resend code"): the full 44 pt
 * row takes the touch, not just the letters, and the label dims while pressed.
 * Disabled: mute grey at full strength (6:1 on light and dark sheets), not a faded accent, so it
 * stays readable while clearly out of play. The label takes its colour from `LocalContentColor`.
 */
@Composable
fun TextLinkButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minHeight: Dp = 44.dp,
    fullWidth: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val alpha by animateFloatAsState(if (pressed) 0.55f else 1f, Motion.select(), label = "textLink")
    Row(
        modifier
            .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
            .defaultMinSize(minHeight = minHeight)
            .graphicsLayer { this.alpha = alpha }
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(DS.Space.xs, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (enabled) {
            content()
        } else {
            CompositionLocalProvider(LocalContentColor provides DS.palette.mute) { content() }
        }
    }
}

/** [TextLinkButton] with a text label, in the accent ink by default. */
@Composable
fun TextLinkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = DS.palette.accentInk,
    style: TextStyle = TextStyles.body.semibold,
    enabled: Boolean = true,
    minHeight: Dp = 44.dp,
    fullWidth: Boolean = false,
) {
    TextLinkButton(onClick, modifier, enabled, minHeight, fullWidth) {
        Text(text, style = style, color = if (enabled) color else DS.palette.mute)
    }
}

// MARK: - Chips

@Composable
fun SportChip(
    sport: Sport,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onDark: Boolean = false,
    /** Unselected fill override (e.g. sage chips on a white block). */
    fill: Color? = null,
    /** How the name ends when the chip is squeezed ([SportChipsLine]'s last resort). */
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val p = DS.palette
    val foreground = when {
        selected -> p.onLime
        onDark -> Color.White
        else -> p.ink
    }
    val background = when {
        selected -> p.lime
        fill != null -> fill
        onDark -> Color.White.copy(alpha = 0.16f)
        else -> p.canvas
    }
    // Selection is a colour change: `Motion.select`, never a spring.
    val fg by animateColorAsState(foreground, Motion.select(), label = "chipInk")
    val bg by animateColorAsState(background, Motion.select(), label = "chipFill")
    val name = sport.displayName
    Row(
        modifier
            .drawBehind { drawRoundRect(bg, cornerRadius = CornerRadius(size.height / 2)) }
            .padding(horizontal = DS.Space.md, vertical = DS.Space.sm)
            .clearAndSetSemantics { contentDescription = name },
        horizontalArrangement = Arrangement.spacedBy(DS.Space.xs + 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DrafftIcon(sport.symbol, size = symbolBox(15f), tint = fg)
        Text(name, style = TextStyles.subheadline.semibold, color = fg, maxLines = 1, softWrap = false, overflow = overflow)
    }
}

// MARK: - Text field

/**
 * The one field pattern: label above (subheadline semibold), the lifted-white bordered box (52 pt,
 * radius md), the error under it. The whole box, padding included, focuses the field. Inside a
 * `FocusScrollView` it scrolls itself clear of the keyboard when focused.
 */
@Composable
fun DrafftField(
    title: String,
    text: String,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    prompt: String = "",
    isSecure: Boolean = false,
    error: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    submitLabel: ImeAction = ImeAction.Next,
    /** The server's length limit for the field, if any: typing stops there. */
    limit: Int? = null,
    onSubmit: () -> Unit = {},
) {
    var revealed by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val p = DS.palette
    val plain = isSecure || keyboard == KeyboardType.Email
    val border by animateColorAsState(
        when {
            error != null -> p.negative
            focused -> p.ink
            else -> p.ink.copy(alpha = 0.35f)
        },
        Motion.gentle(), label = "fieldBorder",
    )
    val width by animateDpAsState(if (focused || error != null) 2.dp else 1.dp, Motion.gentle(), label = "fieldWidth")
    val shape = RoundedCornerShape(DS.Radius.md)

    Column(modifier.revealsOnFocus(focused), verticalArrangement = Arrangement.spacedBy(DS.Space.xs + 2.dp)) {
        Text(title, style = TextStyles.subheadline.semibold, color = p.ink)
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 52.dp)
                .background(p.field, shape)
                .border(width, border, shape)
                .clip(shape)
                // The padding around the text counts too: the whole field focuses it.
                .clickable(remember { MutableInteractionSource() }, indication = null) { focus.requestFocus() }
                .padding(start = DS.Space.lg, end = if (isSecure) 0.dp else DS.Space.lg),
            horizontalArrangement = Arrangement.spacedBy(DS.Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = text,
                onValueChange = { onTextChange(if (limit != null) it.limited(limit) else it) },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focus)
                    .onFocusChanged { focused = it.isFocused },
                textStyle = TextStyles.body.copy(color = p.ink),
                singleLine = true,
                cursorBrush = SolidColor(p.accentInk),
                visualTransformation = if (isSecure && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    capitalization = if (plain) KeyboardCapitalization.None else KeyboardCapitalization.Words,
                    autoCorrectEnabled = !plain,
                    keyboardType = if (isSecure) KeyboardType.Password else keyboard,
                    imeAction = submitLabel,
                ),
                keyboardActions = KeyboardActions(onAny = {
                    onSubmit()
                    defaultKeyboardAction(submitLabel)
                }),
                decorationBox = { inner ->
                    Box(Modifier.defaultMinSize(minHeight = 52.dp), contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty() && prompt.isNotEmpty()) {
                            Text(prompt, style = TextStyles.body, color = p.mute, maxLines = 1)
                        }
                        inner()
                    }
                },
            )
            if (isSecure) {
                PressScaleButton(
                    onClick = { revealed = !revealed },
                    modifier = Modifier.size(44.dp),
                    scale = 1f,
                    contentDescription = if (revealed) L("Hide password") else L("Show password"),
                ) {
                    DrafftIcon(if (revealed) "eye-closed" else "eye", tint = p.body)
                }
            }
        }
        FieldError(error)
    }
}

/** The error line under a field: slides in from under the box. */
@Composable
private fun FieldError(error: String?) {
    // The last error stays drawn while it fades out.
    val last = remember { arrayOfNulls<String>(1) }
    if (error != null) last[0] = error
    AnimatedVisibility(
        visible = error != null,
        enter = fadeIn(Motion.snappy()) + slideInVertically(Motion.snappy()) { -it / 2 },
        exit = fadeOut(Motion.snappy()) + slideOutVertically(Motion.snappy()) { -it / 2 },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(DS.Space.xs), verticalAlignment = Alignment.CenterVertically) {
            DrafftIcon("danger-circle", size = symbolBox(13f), tint = DS.palette.negative)
            Text(last[0].orEmpty(), style = TextStyles.footnote.medium, color = DS.palette.negative)
        }
    }
}

// MARK: - Photo

/**
 * Photo that fills its frame without distorting. [name] is a bundled image name, a file path
 * (starting with "/") for photos the user picked, or a link for photos on the server. Give [side]
 * (the frame's shorter side) for small displays: a downsampled copy is drawn instead of the full
 * photo. [blur] (with [side]) draws a copy with the blur baked in, instead of a live blur.
 * [priority]: download order among photos waiting (the deck: the card in play first).
 */
@Composable
fun Photo(
    name: String,
    modifier: Modifier = Modifier,
    side: Dp? = null,
    blur: Dp = 0.dp,
    priority: Images.Priority = Images.Priority.NORMAL,
) {
    val fraction = if (blur > 0.dp) blur / max(side ?: 200.dp, 1.dp) else 0f
    Box(modifier.clipToBounds().clearAndSetSemantics { }) {
        if (name.startsWith("http") || name.startsWith("/")) {
            // Blurred at decode time, never a live blur (locked likes).
            LoadedPhoto(name, fraction, priority)
        } else {
            BundledPhoto(name, side, fraction)
        }
    }
}

@Composable
private fun BundledPhoto(name: String, side: Dp?, fraction: Float) {
    val res = BundledImages.resource(name)
    if (res == null) {
        Box(Modifier.fillMaxSize().background(DS.palette.canvasSoft))
        return
    }
    val context = LocalPlatformContext.current
    val sidePx = side?.let { with(LocalDensity.current) { it.roundToPx() } }
    if (fraction > 0f || sidePx != null || ImageStore.isPrepared(context, name)) {
        val request = remember(name, sidePx, fraction) { ImageStore.request(context, name, sidePx, fraction) }
        AsyncImage(request, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    } else {
        Image(painterResource(res), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

/**
 * A photo on the server (`http…`) or picked on this phone (`/…`), through `ImageStore`: the copy the
 * frame needs, decoded in the background at the frame's size, capped caches. A copy already in memory
 * shows on the first frame; otherwise its ThumbHash preview ([PhotoUrls.preview]), or a sage tile,
 * stands in until it's there. On a slow connection a large frame first shows a small copy
 * ([ImageStore.preview]), sharp enough to read the photo, while the right one arrives.
 */
@Composable
private fun LoadedPhoto(name: String, blur: Float, priority: Images.Priority) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val boundedWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else 0
        val boundedHeight = if (constraints.hasBoundedHeight) constraints.maxHeight else 0
        val fallback = maxOf(boundedWidth, boundedHeight).takeIf { it > 0 } ?: 1440
        val width = boundedWidth.takeIf { it > 0 } ?: fallback
        val height = boundedHeight.takeIf { it > 0 } ?: fallback
        val context = LocalPlatformContext.current
        val request = remember(name, width, height, blur, priority) {
            ImageStore.remoteRequest(context, name, width, height, priority, blur)
        }
        val painter = rememberAsyncImagePainter(request, contentScale = ContentScale.Crop)
        val state by painter.state.collectAsState()
        val ready = state is AsyncImagePainter.State.Success
        // Already in memory: no fade. Otherwise a 0.2 s ease-out once decoded.
        val readyAtFirstFrame = remember(request) { ready }
        val alpha by animateFloatAsState(
            if (ready) 1f else 0f,
            if (readyAtFirstFrame) tween(0) else tween(200, easing = Motion.EaseOut),
            label = "photoFade",
        )
        val preview = remember(name) { PhotoUrls.preview(name) }
        Box(Modifier.fillMaxSize().background(DS.palette.canvasSoft))
        if (!ready && preview != null) {
            Image(preview, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        if (!ready && blur == 0f && min(maxWidth, maxHeight) >= 200.dp && NetworkQuality.shared.isLimited) {
            val small = remember(name, width, height) { ImageStore.preview(context, name, width, height) }
            if (small != null) {
                AsyncImage(small, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        Image(
            painter,
            contentDescription = null,
            modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = if (readyAtFirstFrame) 1f else alpha },
            contentScale = ContentScale.Crop,
        )
    }
}

/** A chat photo or poster from its bytes, decoded off the main thread at the bubble's size (see [MessageImage]). */
@Composable
fun MessagePhoto(id: String, data: ByteArray, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.background(DS.palette.night).clipToBounds()) {
        val side = maxOf(
            if (constraints.hasBoundedWidth) constraints.maxWidth else 0,
            if (constraints.hasBoundedHeight) constraints.maxHeight else 0,
        ).takeIf { it > 0 } ?: 1080
        val context = LocalPlatformContext.current
        val request = remember(id, side) { MessageImage.request(context, id, data, side) }
        AsyncImage(request, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
fun Avatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    ring: Boolean = false,
) {
    val onNight = LocalIsNightSurface.current
    val p = DS.palette
    Box(
        modifier
            .then(if (ring) Modifier.border(2.5.dp, if (onNight) p.accentOnNight else p.lime, CircleShape).padding(3.dp) else Modifier),
    ) {
        Photo(name, Modifier.size(size).clip(CircleShape), side = size)
    }
}

// MARK: - Text that never truncates

/**
 * A label and a trailing value side by side while both fit in full; otherwise the value moves
 * under the label. Neither is ever cut with "…", whatever the language.
 */
@Composable
fun AdaptiveRow(
    modifier: Modifier = Modifier,
    spacing: Dp = DS.Space.sm,
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
) {
    Layout(contents = listOf(leading, trailing), modifier = modifier) { (lead, trail), constraints ->
        val l = lead.firstOrNull()
        val t = trail.firstOrNull()
        val gap = spacing.roundToPx()
        val lw = l?.maxIntrinsicWidth(Constraints.Infinity) ?: 0
        val tw = t?.maxIntrinsicWidth(Constraints.Infinity) ?: 0
        val fits = !constraints.hasBoundedWidth || lw + gap + tw <= constraints.maxWidth
        if (fits) {
            val lp = l?.measure(Constraints())
            val tp = t?.measure(Constraints())
            val width = if (constraints.hasBoundedWidth) constraints.maxWidth else (lw + gap + tw)
            val lBase = lp?.get(FirstBaseline)?.takeIf { it != androidx.compose.ui.layout.AlignmentLine.Unspecified } ?: 0
            val tBase = tp?.get(FirstBaseline)?.takeIf { it != androidx.compose.ui.layout.AlignmentLine.Unspecified } ?: 0
            val base = maxOf(lBase, tBase)
            val height = maxOf((lp?.height ?: 0) + base - lBase, (tp?.height ?: 0) + base - tBase)
            layout(width, height.coerceAtLeast(constraints.minHeight)) {
                lp?.place(0, base - lBase)
                tp?.place(width - tp.width, base - tBase)
            }
        } else {
            val loose = constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity)
            val lp = l?.measure(loose)
            val tp = t?.measure(loose)
            val between = 2.dp.roundToPx()
            val height = (lp?.height ?: 0) + between + (tp?.height ?: 0)
            layout(constraints.maxWidth, height) {
                lp?.place(0, 0)
                tp?.place(0, (lp?.height ?: 0) + between)
            }
        }
    }
}

// MARK: - Sports line

/** Sport names on one line, never truncated: shows as many as fit, then "+X". */
@Composable
fun SportsLine(
    sports: List<Sport>,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyles.subheadline,
    color: Color = DS.palette.body,
) {
    val measurer = rememberTextMeasurer()
    val all = sports.joinToString(", ") { it.displayName }
    BoxWithConstraints(modifier.clearAndSetSemantics { contentDescription = all }) {
        val max = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        val text = remember(sports, max, style) {
            var pick = ""
            for (shown in sports.size downTo 1) {
                val names = sports.take(shown).joinToString(", ") { it.displayName }
                val rest = sports.size - shown
                val candidate = if (rest > 0) "$names +$rest" else names
                pick = candidate
                if (measurer.measure(candidate, style, maxLines = 1, softWrap = false).size.width <= max) break
            }
            pick
        }
        Text(text, style = style, color = color, maxLines = 1, softWrap = false)
    }
}

// MARK: - Checkbox

/** A checkbox is the one selection mark too: a large [CheckDisc] in a 44 pt target. */
@Composable
fun DrafftCheckbox(isOn: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.size(44.dp), contentAlignment = Alignment.Center) {
        CheckDisc(isOn, size = 28.dp, ring = DS.palette.ink.copy(alpha = 0.45f))
    }
}

// MARK: - Check disc

/**
 * The one selection mark: an accent disc with an on-accent tick when on, a ring when off.
 * The tick is always drawn in on-lime, so it reads on any surface (white, sage, night).
 */
@Composable
fun CheckDisc(
    isOn: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    /** Ring colour when off (lighter on night surfaces). */
    ring: Color = DS.palette.ink.copy(alpha = 0.25f),
    /** On a lime row the disc inverts (ink disc, lime tick), so it never melts into the fill. */
    onLimeFill: Boolean = false,
) {
    val onNight = LocalIsNightSurface.current
    val p = DS.palette
    val accent = if (onNight) p.accentOnNight else p.lime
    val onAccent = if (onNight) p.onAccentOnNight else p.onLime
    val on by animateFloatAsState(if (isOn) 1f else 0f, Motion.springOf(0.12, 1f), label = "checkDisc")
    Box(modifier.size(size).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - on }.border(2.dp, ring, CircleShape))
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = on }
                .background(if (onLimeFill) onAccent else accent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            DrafftIcon("check", size = size * 0.46f * 1.2f, tint = if (onLimeFill) accent else onAccent)
        }
    }
}

/**
 * A changing label: its digits roll while the wording stays the same; when the words change
 * ("Any" to "3 selected", "Resend in 0:05" to "Resend code") the old and new labels cross-fade in
 * place instead, so a longer old label never slides out of its block. (`rollingDigits` on iOS.)
 * [rollsDigits] false: the digits change in place, nothing moves each tick (a countdown); only a
 * change of wording cross-fades.
 */
@Composable
fun RollingText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    countsDown: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    rollsDigits: Boolean = true,
) {
    AnimatedContent(
        targetState = text,
        modifier = modifier,
        // Same wording, same content: a new count redraws in place, with no transition.
        contentKey = { if (rollsDigits) it else it.wording },
        transitionSpec = {
            if (initialState.wording == targetState.wording) {
                val dir = if (countsDown) -1 else 1
                (slideInVertically(Motion.snappy()) { dir * it / 2 } + fadeIn(Motion.gentle()))
                    .togetherWith(slideOutVertically(Motion.snappy()) { -dir * it / 2 } + fadeOut(Motion.gentle()))
            } else {
                fadeIn(Motion.gentle()).togetherWith(fadeOut(Motion.gentle()))
            }
        },
        label = "rollingText",
    ) { value ->
        Text(value, style = style, color = color, maxLines = maxLines)
    }
}

/** The words without the numbers, to tell a count change from a wording change. */
val String.wording: String get() = filter { !it.isDigit() }

/** How many characters a person sees (grapheme clusters), like Swift's `String.count`. */
val String.characterCount: Int
    get() {
        if (all { it.code < 0x300 }) return length
        val it = BreakIterator.getCharacterInstance()
        it.setText(this)
        var n = 0
        while (it.next() != BreakIterator.DONE) n++
        return n
    }

/**
 * At most [limit] characters as the database counts them (Unicode code points, `char_length`),
 * never splitting a character.
 */
fun String.limited(limit: Int): String {
    if (codePointCount(0, length) <= limit) return this
    val it = BreakIterator.getCharacterInstance()
    it.setText(this)
    var end = 0
    var points = 0
    var next = it.next()
    while (next != BreakIterator.DONE) {
        val add = codePointCount(end, next)
        if (points + add > limit) break
        points += add
        end = next
        next = it.next()
    }
    return substring(0, end)
}

/**
 * Multi-line field in the [DrafftField] pattern: label above, one bordered box that takes
 * touches everywhere, the character count tucked in its bottom corner.
 */
@Composable
fun DrafftTextArea(
    title: String,
    text: String,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    prompt: String = "",
    limit: Int = 200,
    /** Page fill by default; sage when the area sits on a white block. */
    fill: Color = DS.palette.canvas,
    /** Off when the surrounding block already names the field. */
    showsTitle: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val p = DS.palette
    val count = text.characterCount
    val over = count > limit
    val border by animateColorAsState(
        when {
            over -> p.negative
            focused -> p.ink
            else -> p.ink.copy(alpha = 0.35f)
        },
        Motion.gentle(), label = "areaBorder",
    )
    val width by animateDpAsState(if (focused || over) 2.dp else 1.dp, Motion.gentle(), label = "areaWidth")
    val shape = RoundedCornerShape(DS.Radius.lg)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(DS.Space.xs + 2.dp)) {
        if (showsTitle) Text(title, style = TextStyles.subheadline.semibold, color = p.ink)
        Column(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 140.dp)
                .background(fill, shape)
                .border(width, border, shape)
                .clip(shape)
                .clickable(remember { MutableInteractionSource() }, indication = null) { focus.requestFocus() }
                .padding(DS.Space.lg)
                .revealsOnFocus(focused),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(DS.Space.xs),
        ) {
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .onFocusChanged { focused = it.isFocused },
                textStyle = TextStyles.body.copy(color = p.ink),
                minLines = 4,
                maxLines = 8,
                cursorBrush = SolidColor(p.accentInk),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty() && prompt.isNotEmpty()) Text(prompt, style = TextStyles.body, color = p.mute)
                        inner()
                    }
                },
            )
            RollingText(
                if (over) L("%d too many", count - limit) else L("%d left", limit - count),
                style = TextStyles.caption.semibold.monospacedDigits,
                // Body grey, not mute: the area often sits on sage, where mute is under 4.5:1.
                color = if (over) p.negative else p.body,
                countsDown = !over,
            )
        }
    }
}

/** Marks a chip or row as selected for TalkBack (`.accessibilityAddTraits(.isSelected)`). */
internal fun Modifier.selectedTrait(on: Boolean): Modifier = if (on) semantics { selected = true } else this
