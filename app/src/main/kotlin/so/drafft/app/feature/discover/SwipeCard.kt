package so.drafft.app.feature.discover

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import so.drafft.core.data.audio.AudioPlayback
import so.drafft.core.data.location.LocationPrivacy
import so.drafft.core.data.platform.Haptics
import so.drafft.core.model.L
import so.drafft.core.model.Profile
import so.drafft.core.model.Sport
import so.drafft.core.model.clock
import so.drafft.core.ui.components.Photo
import so.drafft.core.ui.components.PressScaleButton
import so.drafft.core.ui.components.RollingText
import so.drafft.core.ui.components.SportChip
import so.drafft.core.ui.components.SuperLikeMark
import so.drafft.core.ui.components.glass
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.LocalReduceMotion
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.NightSurface
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.bold
import so.drafft.core.ui.theme.display
import so.drafft.core.ui.theme.displayBold
import so.drafft.core.ui.theme.medium
import so.drafft.core.ui.theme.monospacedDigits
import kotlin.math.max

// Port of Drafft/Features/Discover/SwipeCard.swift.

/**
 * A profile card in the deck. [progress] (-1 pass ... 1 like) drives the stamps; it's read at draw
 * time, so a drag moves them without recomposing the card.
 */
@Composable
fun SwipeCard(
    profile: Profile,
    me: Profile,
    progress: () -> Float,
    isTop: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = DS.palette
    val shape = RoundedCornerShape(DS.Radius.xl)
    val label = L("%s, %d. %s", profile.name, profile.age, profile.sports.joinToString(", ") { it.sport.displayName })
    NightSurface {
        Box(modifier.clip(shape).semantics { contentDescription = label }) {
            Photo(profile.portrait, Modifier.fillMaxSize())

            // Scrims so the identity (top) and sports (bottom) stay readable on any photo.
            val night = p.night
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        // design-lint: allow gradient - photo scrims for the identity and sports
                        Brush.verticalGradient(
                            0f to night.copy(alpha = 0.72f),
                            0.3f to Color.Transparent,
                            0.62f to Color.Transparent,
                            1f to night.copy(alpha = 0.85f),
                        ),
                    ),
            )

            // The whole card opens the full profile; photos are browsed there.
            if (LocalCardInteractive.current) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onOpen)
                        .clearAndSetSemantics { },
                )
            }

            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .padding(start = DS.Space.xl, top = DS.Space.md, end = DS.Space.md),
                horizontalArrangement = Arrangement.spacedBy(DS.Space.md),
                verticalAlignment = Alignment.Top,
            ) {
                ProfileIdentity(
                    profile = profile,
                    showsSuperLike = true,
                    superLikeActive = isTop,
                    modifier = Modifier.weight(1f).padding(top = DS.Space.sm),
                )
                VoicePill(profile)
            }

            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(DS.Space.xl)) {
                SportChipsPreview(
                    sports = profile.sports.map { it.sport },
                    highlighted = remember(me.sports) { me.sports.map { it.sport }.toSet() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Stamps(progress, Modifier.align(Alignment.Center))
        }
    }
}

// MARK: Pieces

@Composable
private fun VoicePill(profile: Profile) {
    val url = remember(profile.voiceIntro) { profile.voiceIntro?.let(AudioPlayback::url) } ?: return
    val audio = koinInject<AudioPlayback>()
    val p = DS.palette
    val playing = audio.isCurrent(url) && audio.isPlaying
    val ink = if (playing) p.onAccentOnNight else Color.White
    val pill: @Composable () -> Unit = {
        Row(
            Modifier
                .defaultMinSize(minHeight = 36.dp)
                // Glass over the photo; a dark tint keeps the white legible on bright shots.
                // Accent while playing, so the active state reads at a glance.
                .glass(CircleShape, tint = if (playing) p.accentOnNight else Color.Black.copy(alpha = 0.3f))
                .padding(horizontal = DS.Space.md),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedContent(
                targetState = playing,
                transitionSpec = { fadeIn(Motion.gentle()) togetherWith fadeOut(Motion.gentle()) },
                label = "voiceGlyph",
            ) { on ->
                DrafftIcon(if (on) "pause" else "soundwave", size = (13f * 1.2f).dp, tint = ink)
            }
            RollingText(
                if (playing) audio.elapsed.clock else profile.voiceDuration.clock,
                style = TextStyles.footnote.bold.monospacedDigits,
                color = ink,
                countsDown = false,
                maxLines = 1,
            )
        }
    }
    if (!LocalCardInteractive.current) {
        Box(Modifier.defaultMinSize(minHeight = 44.dp), contentAlignment = Alignment.Center) { pill() }
        return
    }
    PressScaleButton(
        onClick = {
            Haptics.tap()
            audio.toggle(url)
        },
        modifier = Modifier.defaultMinSize(minHeight = 44.dp),
        contentDescription = if (playing) L("Pause voice intro") else L("Play %s's voice intro", profile.name),
    ) { pill() }
}

/**
 * False for cards that must let every touch through (`.allowsHitTesting(false)`): the cards
 * waiting behind the top one and the ones finishing their flight. They then carry no touch target
 * at all, so a tap reaches whatever lies under them.
 */
internal val LocalCardInteractive = compositionLocalOf { true }

@Composable
private fun Stamps(progress: () -> Float, modifier: Modifier = Modifier) {
    val p = DS.palette
    Box(
        modifier
            .fillMaxWidth()
            .height(120.dp)
            .padding(horizontal = DS.Space.xl)
            .clearAndSetSemantics { },
    ) {
        Stamp(
            L("LIKE"), p.like, p.onLike, angle = -14f,
            Modifier.align(Alignment.TopStart).graphicsLayer {
                val v = max(0f, progress())
                alpha = (v * 1.4f).coerceAtMost(1f)
                scaleX = 0.8f + v * 0.25f
                scaleY = scaleX
            },
        )
        Stamp(
            L("PASS"), Color.White, p.onLike, angle = 14f,
            Modifier.align(Alignment.TopEnd).graphicsLayer {
                val v = max(0f, -progress())
                alpha = (v * 1.4f).coerceAtMost(1f)
                scaleX = 0.8f + v * 0.25f
                scaleY = scaleX
            },
        )
    }
}

@Composable
private fun Stamp(word: String, color: Color, text: Color, angle: Float, modifier: Modifier) {
    Text(
        word,
        modifier
            .graphicsLayer { rotationZ = angle }
            .background(color, RoundedCornerShape(DS.Radius.md))
            .padding(horizontal = DS.Space.lg, vertical = DS.Space.xs),
        style = display(40f),
        color = text,
        maxLines = 1,
        softWrap = false,
    )
}

/** Sports only (no levels), capped at two lines; the rest collapses into a "+X" chip. */
@Composable
fun SportChipsPreview(
    sports: List<Sport>,
    /** Sports shown in the accent (the ones you also do). */
    highlighted: Set<Sport> = emptySet(),
    maxLines: Int = 2,
    modifier: Modifier = Modifier,
) {
    val label = sports.joinToString(", ") { if (it in highlighted) L("%s, you do it too", it.displayName) else it.displayName }
    OverflowFlow(
        itemCount = sports.size,
        modifier = modifier.clearAndSetSemantics { contentDescription = label },
        spacing = DS.Space.xs + 2.dp,
        maxLines = maxLines,
    ) {
        sports.forEach { SportChip(it, selected = it in highlighted, onDark = true) }
        // One candidate "+X" badge per possible overflow count; the layout shows the one it needs.
        for (hidden in 1 until max(sports.size, 1)) {
            Text(
                "+$hidden",
                Modifier
                    .background(Color.White.copy(alpha = 0.16f), CircleShape)
                    .padding(horizontal = DS.Space.md, vertical = DS.Space.sm),
                style = TextStyles.subheadline.bold,
                color = Color.White,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/**
 * Flow layout limited to [maxLines]. [content] emits [itemCount] items, then the "+X" badges for 1,
 * 2... hidden items. Shows as many items as fit, plus the matching badge; the rest aren't placed.
 */
@Composable
fun OverflowFlow(
    itemCount: Int,
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    maxLines: Int = 2,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val placeables = measurables.map { it.measure(Constraints()) }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        val items = minOf(itemCount, placeables.size)

        class Plan(val positions: Map<Int, Pair<Int, Int>>, val width: Int, val height: Int)

        fun layout(indices: List<Int>): Plan? {
            val positions = HashMap<Int, Pair<Int, Int>>()
            var x = 0
            var y = 0
            var rowH = 0
            var lines = 1
            var w = 0
            for (i in indices) {
                val s = placeables[i]
                if (x + s.width > width && x > 0) {
                    lines += 1
                    if (lines > maxLines) return null
                    x = 0
                    y += rowH + gap
                    rowH = 0
                }
                positions[i] = x to y
                x += s.width + gap
                rowH = maxOf(rowH, s.height)
                w = maxOf(w, x - gap)
            }
            return Plan(positions, w, y + rowH)
        }

        var plan = Plan(emptyMap(), 0, 0)
        for (shown in items downTo 0) {
            val indices = (0 until shown).toMutableList()
            val hidden = items - shown
            val badge = itemCount + hidden - 1
            if (hidden > 0 && badge < placeables.size) indices += badge
            val p = layout(indices)
            if (p != null) {
                plan = p
                break
            }
        }
        val layoutWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else plan.width
        layout(layoutWidth, plan.height.coerceIn(constraints.minHeight, maxOf(constraints.minHeight, plan.height))) {
            plan.positions.forEach { (i, pos) -> placeables[i].place(pos.first, pos.second) }
        }
    }
}

/** Name, age and area, as shown on the deck card. Reused wherever a profile is shown over its photo. */
@Composable
fun ProfileIdentity(
    profile: Profile,
    nameSize: Float = 34f,
    showsLocation: Boolean = true,
    /** Deck only: a red super like disc right after the age when they super liked you. */
    showsSuperLike: Boolean = false,
    /** The super like disc pops in when this turns true (the card reaching the top of the deck). */
    superLikeActive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(DS.Space.xs)) {
        NameAgeLine(
            profile = profile,
            nameSize = nameSize,
            nameColor = Color.White,
            ageColor = Color.White.copy(alpha = 0.8f),
            showsBadge = showsSuperLike && profile.superLikedMe,
            badgeActive = superLikeActive,
        )
        if (showsLocation) {
            Text(
                L("%s, %s", profile.neighborhood, LocationPrivacy.rounded(profile.distanceKm)),
                style = TextStyles.subheadline.medium,
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 2,
            )
        }
    }
}

/**
 * Inter Display metrics (units per em 2048): x-height 1118, cap height 1490. The age is sized so its
 * figures stand as tall as the name's lowercase letters (x-height); the disc is as tall as the capitals.
 */
private const val AGE_RATIO = 1118f / 1490f
private const val CAP_HEIGHT = 1490f / 2048f
private const val BADGE = "superLikeBadge"

/**
 * (Optional super like disc,) name, then age, as one run of text: the disc leads at cap height,
 * the age follows the last word of the name at the name's x-height, even when the name wraps.
 *
 * Long names are never truncated and never broken inside a word: the name wraps at spaces and
 * hyphens, and if a single word is wider than the space, the whole line steps down in size just
 * enough for it to fit. The disc is inline content in the text, so it can pop in.
 */
@Composable
fun NameAgeLine(
    profile: Profile,
    nameSize: Float,
    nameColor: Color,
    ageColor: Color,
    showsBadge: Boolean = false,
    badgeActive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalReduceMotion.current
    val pop = remember { Animatable(0f) }
    val active by rememberUpdatedState(badgeActive)
    LaunchedEffect(showsBadge, badgeActive) {
        if (!showsBadge || !active || pop.value >= 1f || pop.isRunning) return@LaunchedEffect
        if (reduceMotion) {
            pop.snapTo(1f)
        } else {
            delay(120)
            pop.animateTo(1f, Motion.springOf(0.34, 0.55f))
        }
    }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val a11y = if (showsBadge) L("%s, %d, super liked you", profile.name, profile.age) else L("%s, %d", profile.name, profile.age)

    BoxWithConstraints(modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = a11y }) {
        val width = constraints.maxWidth
        val scale = remember(profile.name, profile.age, nameSize, showsBadge, width, measurer, density) {
            if (width <= 0 || width == Constraints.Infinity) return@remember 1f
            val two = with(density) { 2.dp.toPx() }
            fun textWidth(text: String, bold: Boolean, size: Float): Float =
                measurer.measure(AnnotatedString(text), if (bold) displayBold(size) else display(size), softWrap = false).size.width.toFloat()
            // Unbreakable pieces of the name: split at spaces, and after hyphens.
            val words = mutableListOf<String>()
            var current = StringBuilder()
            for (ch in profile.name) {
                if (ch == ' ') {
                    if (current.isNotEmpty()) words += current.toString()
                    current = StringBuilder()
                } else {
                    current.append(ch)
                    if (ch == '-') {
                        words += current.toString()
                        current = StringBuilder()
                    }
                }
            }
            if (current.isNotEmpty()) words += current.toString()
            // Widest unbreakable run at full size: any word, or the last word + age (+ disc) glued together.
            val widths = words.map { textWidth(it, false, nameSize) }.toMutableList()
            if (widths.isEmpty()) return@remember 1f
            // The disc is glued to the first word, the age to the last.
            if (showsBadge) widths[0] += with(density) { (nameSize * CAP_HEIGHT).sp.toPx() } + textWidth(" ", false, nameSize)
            widths[widths.lastIndex] += textWidth("  ${profile.age}", true, nameSize * AGE_RATIO)
            val widest = widths.max()
            if (widest <= width - two) 1f else max(0.55f, (width - two) / widest)
        }
        val n = nameSize * scale
        val a = nameSize * AGE_RATIO * scale
        val badge = n * CAP_HEIGHT
        val nameStyle = display(n)
        val text = buildAnnotatedString {
            if (showsBadge) {
                appendInlineContent(BADGE, "●")
                withStyle(nameStyle.toSpanStyle().copy(color = nameColor)) { append(" ") }
            }
            withStyle(nameStyle.toSpanStyle().copy(color = nameColor)) { append(profile.name) }
            withStyle(displayBold(a).toSpanStyle().copy(color = ageColor)) { append("  ${profile.age}") }
        }
        // The disc leads the line, exactly as tall as the capitals, sitting on the baseline.
        val inline = if (showsBadge) {
            mapOf(
                BADGE to InlineTextContent(Placeholder(badge.sp, badge.sp, PlaceholderVerticalAlign.AboveBaseline)) {
                    val side = with(density) { badge.sp.toDp() }
                    SuperLikeBadge(side, Modifier.graphicsLayer {
                        val t = pop.value
                        val s = 0.2f + 0.8f * t
                        alpha = (t * 1.6f).coerceIn(0f, 1f)
                        scaleX = s
                        scaleY = s
                        rotationZ = -30f * (1f - t)
                    })
                },
            )
        } else {
            emptyMap()
        }
        Text(text, Modifier.fillMaxWidth(), style = nameStyle.copy(color = nameColor), inlineContent = inline)
    }
}

/** The super like disc, drawn inline in a name line: the mark on a red disc. */
@Composable
private fun SuperLikeBadge(size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(DS.palette.negative, CircleShape), contentAlignment = Alignment.Center) {
        SuperLikeMark(Modifier.offset(x = -size * 0.09f), size = size * 0.38f, color = Color.White)
    }
}
