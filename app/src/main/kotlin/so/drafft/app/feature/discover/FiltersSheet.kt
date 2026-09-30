package so.drafft.app.feature.discover

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import so.drafft.core.data.platform.Haptics
import so.drafft.core.model.Audience
import so.drafft.core.model.DiscoverFilters
import so.drafft.core.model.L
import so.drafft.core.model.Sport
import so.drafft.core.ui.LocalAppModel
import so.drafft.core.ui.components.AdaptiveRow
import so.drafft.core.ui.components.DrafftButton
import so.drafft.core.ui.components.EdgeBars
import so.drafft.core.ui.components.FlowLayout
import so.drafft.core.ui.components.FocusScrollView
import so.drafft.core.ui.components.LocalSheetDismiss
import so.drafft.core.ui.components.PressScaleButton
import so.drafft.core.ui.components.RollingText
import so.drafft.core.ui.components.SheetNavBar
import so.drafft.core.ui.components.SportPicker
import so.drafft.core.ui.components.TextLinkButton
import so.drafft.core.ui.components.draftTrail
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.bold
import so.drafft.core.ui.theme.monospacedDigits
import so.drafft.core.ui.theme.semibold
import kotlin.math.abs
import kotlin.math.roundToInt

// Port of Drafft/Features/Discover/FiltersSheet.swift.

/**
 * Discover filters. Edits a draft; Discover shows the result (or the too-tight message) once applied.
 * Present it in a `DrafftSheet`.
 */
@Composable
fun FiltersSheet(filters: DiscoverFilters, modifier: Modifier = Modifier) {
    val app = LocalAppModel.current
    val dismiss = LocalSheetDismiss.current
    var draft by rememberSaveable(stateSaver = FiltersSaver) { mutableStateOf(filters) }
    val scroll = rememberScrollState()
    val p = DS.palette

    EdgeBars(
        scroll = scroll,
        modifier = modifier.fillMaxSize().background(p.canvasSoft),
        topBar = { SheetNavBar(L("Filters"), onClose = dismiss) },
        bottomBar = {
            Column(
                Modifier.padding(start = DS.Space.xl, end = DS.Space.xl, top = DS.Space.md),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(DS.Space.sm),
            ) {
                DrafftButton(
                    text = L("Apply filters"),
                    onClick = {
                        Haptics.success()
                        app.filters = draft
                        dismiss()
                    },
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .draftTrail(RoundedCornerShape(DS.Radius.xl), step = androidx.compose.ui.unit.DpOffset((-6).dp, 0.dp)),
                )
                TextLinkButton(
                    L("Clear filters"),
                    onClick = {
                        Haptics.tap()
                        draft = draft.cleared()
                    },
                    color = p.accentInk,
                    style = TextStyles.subheadline.semibold,
                    enabled = draft != draft.cleared(),
                )
            }
        },
        navigationEdge = true,
        // The sheet already sits below the status bar and above the navigation bar.
        windowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        FocusScrollView(
            modifier = Modifier.fillMaxSize(),
            state = scroll,
            contentPadding = PaddingValues(
                start = DS.Space.lg,
                end = DS.Space.lg,
                top = padding.calculateTopPadding() + DS.Space.sm,
                bottom = padding.calculateBottomPadding() + DS.Space.sm,
            ),
            verticalArrangement = Arrangement.spacedBy(DS.Space.md),
        ) {
            FilterBlock(L("Distance"), "map-point", value = draft.distanceLabel) {
                DistanceSlider(value = draft.maxDistanceKm, onValueChange = { draft = draft.copy(maxDistanceKm = it) })
            }

            val ages = draft.ages
            FilterBlock(
                L("Age"), "user-rounded",
                value = "${ages.first}–${ages.last}${if (ages.last == DiscoverFilters.ageBounds.last) "+" else ""}",
            ) {
                RangeSlider(range = ages, onRangeChange = { draft = draft.withAges(it) }, bounds = DiscoverFilters.ageBounds)
            }

            FilterBlock(L("Show me"), "eye") {
                Chips(
                    options = Audience.entries.map { it.title },
                    isOn = { it == draft.audience.title },
                    toggle = { label -> draft = draft.copy(audience = Audience.entries.firstOrNull { it.title == label } ?: Audience.EVERYONE) },
                )
            }

            FilterBlock(
                L("Sports"), "running",
                value = if (draft.sports.isEmpty()) L("Any") else L("%d selected", draft.sports.size),
            ) {
                val chosen = draft.sports
                SportPicker(
                    selected = Sport.entries.filter { it in chosen },
                    onToggle = { s -> draft = draft.withSports(if (s in chosen) chosen - s else chosen + s) },
                    chipBackground = p.canvasSoft,
                    collapsedCount = 16,
                )
            }

            FilterBlock(L("In common"), "link-circle") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DS.Space.md)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(L("Only people who do one of my sports"), style = TextStyles.subheadline.semibold, color = p.ink)
                        Text(L("At least one sport in common with you"), style = TextStyles.footnote, color = p.body)
                    }
                    Switch(
                        checked = draft.sharedSportsOnly,
                        onCheckedChange = { draft = draft.copy(sharedSportsOnly = it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = p.onLime,
                            checkedTrackColor = p.lime,
                            checkedBorderColor = p.lime,
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = p.ink.copy(alpha = 0.16f),
                            uncheckedBorderColor = Color.Transparent,
                        ),
                    )
                }
            }
        }
    }
}

/** The draft survives a rotation: saved as the same text the search is kept in between launches. */
private val FiltersSaver = androidx.compose.runtime.saveable.Saver<DiscoverFilters, String>(
    save = { it.encode() },
    restore = { DiscoverFilters.decode(it) },
)

// MARK: Chrome

@Composable
private fun FilterBlock(
    title: String,
    icon: String,
    value: String? = null,
    content: @Composable () -> Unit,
) {
    val p = DS.palette
    Column(
        Modifier
            .fillMaxWidth()
            .background(p.canvas, RoundedCornerShape(DS.Radius.xl))
            .padding(DS.Space.lg),
        verticalArrangement = Arrangement.spacedBy(DS.Space.md),
    ) {
        // Title and value share the line while both fit; a long translation puts the value under it.
        AdaptiveRow(
            leading = {
                Row(horizontalArrangement = Arrangement.spacedBy(DS.Space.sm), verticalAlignment = Alignment.CenterVertically) {
                    DrafftIcon(icon, size = (13f * 1.2f).dp, tint = p.ink)
                    Text(title, Modifier.semantics { heading() }, style = TextStyles.headline, color = p.ink)
                }
            },
            trailing = {
                if (value != null) {
                    RollingText(value, style = TextStyles.subheadline.bold.monospacedDigits, color = p.ink, maxLines = 1)
                }
            },
        )
        content()
    }
}

@Composable
private fun Chips(options: List<String>, isOn: (String) -> Boolean, toggle: (String) -> Unit) {
    val p = DS.palette
    FlowLayout(spacing = DS.Space.sm) {
        options.forEach { label ->
            val on = isOn(label)
            val ink by animateColorAsState(if (on) p.onLime else p.ink, Motion.select(), label = "chipInk")
            val fill by animateColorAsState(if (on) p.lime else p.canvasSoft, Motion.select(), label = "chipFill")
            PressScaleButton(
                onClick = {
                    Haptics.select()
                    toggle(label)
                },
                modifier = Modifier
                    .defaultMinSize(minHeight = 44.dp)
                    .semantics { selected = on },
                scale = 0.94f,
            ) {
                Box(
                    Modifier
                        .defaultMinSize(minHeight = 36.dp)
                        .background(fill, CircleShape)
                        .padding(horizontal = DS.Space.md),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = TextStyles.footnote.semibold, color = ink, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

private val Thumb = 28.dp

/** Two-thumb slider for an integer range. */
@Composable
fun RangeSlider(
    range: IntRange,
    onRangeChange: (IntRange) -> Unit,
    bounds: IntRange,
    modifier: Modifier = Modifier,
) {
    val p = DS.palette
    val current by rememberUpdatedState(range)
    val change by rememberUpdatedState(onRangeChange)
    BoxWithConstraints(modifier.fillMaxWidth().height(44.dp)) {
        val density = LocalDensity.current
        val thumbPx = with(density) { Thumb.toPx() }
        val hitPx = with(density) { 44.dp.toPx() }
        val width = constraints.maxWidth - thumbPx
        val span = (bounds.last - bounds.first).toFloat()
        fun x(v: Int) = (v - bounds.first) / span * width
        fun value(x: Float): Int = bounds.first + ((x / width).coerceIn(0f, 1f) * span).roundToInt()
        val lo = x(range.first)
        val hi = x(range.last)

        Box(Modifier.align(Alignment.CenterStart).padding(horizontal = Thumb / 2).fillMaxWidth().height(6.dp).background(p.ink.copy(alpha = 0.1f), CircleShape))
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset((lo + thumbPx / 2).roundToInt(), 0) }
                .width(with(density) { maxOf(0f, hi - lo).toDp() })
                .height(6.dp)
                .background(p.lime, CircleShape),
        )
        // Horizontal-only, so vertical swipes on the slider still scroll the sheet.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(bounds, width) {
                    var dragging = 0
                    detectHorizontalDragGestures(
                        onDragStart = { at ->
                            val r = current
                            val dLo = abs(at.x - (x(r.first) + thumbPx / 2))
                            val dHi = abs(at.x - (x(r.last) + thumbPx / 2))
                            // Each knob answers on its 44 pt frame only.
                            dragging = when {
                                dLo > hitPx / 2 && dHi > hitPx / 2 -> 0
                                dLo < dHi || (dLo == dHi && at.x < x(r.first) + thumbPx / 2) -> -1
                                else -> 1
                            }
                        },
                        onDragEnd = { dragging = 0 },
                        onDragCancel = { dragging = 0 },
                    ) { change0, _ ->
                        if (dragging == 0) return@detectHorizontalDragGestures
                        change0.consume()
                        val r = current
                        val v = value(change0.position.x - thumbPx / 2)
                        if (dragging < 0) {
                            val new = minOf(v, r.last - 1)
                            if (new != r.first) {
                                Haptics.select()
                                change(new..r.last)
                            }
                        } else {
                            val new = maxOf(v, r.first + 1)
                            if (new != r.last) {
                                Haptics.select()
                                change(r.first..new)
                            }
                        }
                    }
                },
        )
        Knob(
            lo,
            Modifier.semantics {
                contentDescription = L("Minimum age")
                stateDescription = "${range.first}"
                progressBarRangeInfo = ProgressBarRangeInfo(range.first.toFloat(), bounds.first.toFloat()..bounds.last.toFloat(), bounds.last - bounds.first - 1)
                setProgress { target ->
                    val v = target.roundToInt()
                    if (v >= bounds.first && v < current.last) change(v..current.last)
                    true
                }
            },
        )
        Knob(
            hi,
            Modifier.semantics {
                contentDescription = L("Maximum age")
                stateDescription = "${range.last}"
                progressBarRangeInfo = ProgressBarRangeInfo(range.last.toFloat(), bounds.first.toFloat()..bounds.last.toFloat(), bounds.last - bounds.first - 1)
                setProgress { target ->
                    val v = target.roundToInt()
                    if (v <= bounds.last && v > current.first) change(current.first..v)
                    true
                }
            },
        )
    }
}

@Composable
private fun Knob(x: Float, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val offset = with(density) { (44.dp - Thumb).toPx() / 2 }
    Box(
        modifier
            .offset { IntOffset((x - offset).roundToInt(), 0) }
            .size(44.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(Thumb)
                .shadow(3.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.12f))
                .background(Color.White, CircleShape),
        )
    }
}

/** Single-thumb distance slider, 1 km to "50+" (no limit). Custom so it has no tick marks and snaps cleanly. */
@Composable
fun DistanceSlider(
    value: Double,
    onValueChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = DS.palette
    val lower = DiscoverFilters.distanceBounds.start
    val upper = DiscoverFilters.ANY_DISTANCE
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onValueChange)
    Column(
        modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = L("Maximum distance")
                stateDescription = if (value >= upper) L("Any distance") else L("%d kilometres", value.toInt())
                progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), lower.toFloat()..upper.toFloat(), (upper - lower).toInt() - 1)
                setProgress { target ->
                    change(target.toDouble().coerceIn(lower, upper).let { kotlin.math.round(it) })
                    true
                }
            },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(44.dp)) {
            val density = LocalDensity.current
            val thumbPx = with(density) { Thumb.toPx() }
            val width = constraints.maxWidth - thumbPx
            val x = ((minOf(value, upper) - lower) / (upper - lower)).toFloat() * width
            Box(Modifier.align(Alignment.CenterStart).padding(horizontal = Thumb / 2).fillMaxWidth().height(6.dp).background(p.ink.copy(alpha = 0.1f), CircleShape))
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset { IntOffset((thumbPx / 2).roundToInt(), 0) }
                    .width(with(density) { maxOf(0f, x).toDp() })
                    .height(6.dp)
                    .background(p.lime, CircleShape),
            )
            Knob(x, Modifier.align(Alignment.CenterStart))
            // Horizontal-only, so vertical swipes on the slider still scroll the sheet.
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(width) {
                        detectHorizontalDragGestures { change0, _ ->
                            change0.consume()
                            val t = ((change0.position.x - thumbPx / 2) / width).coerceIn(0f, 1f)
                            val new = kotlin.math.round(lower + t * (upper - lower))
                            if (new != current) {
                                change(new)
                                Haptics.select()
                            }
                        }
                    },
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(L("1 km"), style = TextStyles.caption.semibold, color = p.mute)
            Spacer(Modifier.weight(1f))
            DrafftIcon("infinite", size = (12f * 1.2f).dp, tint = p.mute, contentDescription = L("No limit"))
        }
    }
}
