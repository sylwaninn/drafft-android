package so.drafft.app.feature.profile

// Ports Drafft/Features/Profile/ProfileBlocks.swift.

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import so.drafft.core.data.audio.AudioPlayback
import so.drafft.core.data.location.LocationPrivacy
import so.drafft.core.data.platform.Haptics
import so.drafft.core.model.L
import so.drafft.core.model.Profile
import so.drafft.core.model.ProfilePrompt
import so.drafft.core.model.Sport
import so.drafft.core.model.Vitals
import so.drafft.core.model.Waveform
import so.drafft.core.model.clock
import so.drafft.core.ui.components.DraftGlyph
import so.drafft.core.ui.components.FlowLayout
import so.drafft.core.ui.components.NightBlock
import so.drafft.core.ui.components.Photo
import so.drafft.core.ui.components.PressScaleButton
import so.drafft.core.ui.components.RollingText
import so.drafft.core.ui.components.draftBlock
import so.drafft.core.ui.components.draftTrail
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.bold
import so.drafft.core.ui.theme.display
import so.drafft.core.ui.theme.displayBold
import so.drafft.core.ui.theme.monospacedDigits
import so.drafft.core.ui.theme.semibold

/** Section title in the display face. */
@Composable
fun ProfileSectionTitle(title: String, trailing: String? = null, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).semantics { heading() }, style = display(26f), color = DS.palette.ink)
        if (trailing != null) {
            Text(
                trailing,
                Modifier
                    .background(DS.palette.lime, CircleShape)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                style = TextStyles.footnote.bold,
                color = DS.palette.onLime,
            )
        }
    }
}

/** White block holding a section title and its content. Text never sits outside a block. */
@Composable
fun ProfileSectionCard(
    title: String,
    trailing: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(DS.palette.canvas, RoundedCornerShape(DS.Radius.xl))
            .padding(DS.Space.xl),
        verticalArrangement = Arrangement.spacedBy(DS.Space.lg),
    ) {
        ProfileSectionTitle(title, trailing)
        content()
    }
}

// MARK: - Voice

/** Night block: big lime play button with drafting trail, full-width waveform, playback speed. */
@Composable
fun VoiceBlock(profile: Profile, modifier: Modifier = Modifier) {
    val audio = koinInject<AudioPlayback>()
    val url = profile.voiceIntro?.let(AudioPlayback::url)
    val isCurrent = audio.isCurrent(url)
    val playing = isCurrent && audio.isPlaying
    val tick = rememberFrameTick(playing)
    val levels = remember(profile.id) { Waveform.seeded(profile.id, 48) }
    val p = DS.palette

    NightBlock(modifier.fillMaxWidth()) {
        Column(Modifier.padding(DS.Space.xl), verticalArrangement = Arrangement.spacedBy(DS.Space.lg)) {
            Row(horizontalArrangement = Arrangement.spacedBy(DS.Space.lg), verticalAlignment = Alignment.CenterVertically) {
                PressScaleButton(
                    onClick = {
                        if (url != null) {
                            Haptics.tap()
                            audio.toggle(url)
                        }
                    },
                    modifier = Modifier
                        .padding(start = 20.dp)
                        .draftTrail(CircleShape, step = DpOffset((-10).dp, 0.dp))
                        .size(64.dp)
                        .background(p.accentOnNight, CircleShape),
                    contentDescription = if (playing) L("Pause voice intro") else L("Play %s's voice intro", profile.name),
                ) {
                    Crossfade(playing, label = "voiceIntroPlay") { on ->
                        DrafftIcon(if (on) "pause" else "play", size = symbol(24f), tint = p.onAccentOnNight)
                    }
                }

                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(L("Hear %s", profile.name), style = display(24f), color = Color.White)
                    RollingText(
                        L("%s voice intro", (if (isCurrent && audio.elapsed > 0) audio.elapsed else profile.voiceDuration).clock),
                        style = TextStyles.subheadline.semibold.monospacedDigits,
                        color = Color.White.copy(alpha = 0.6f),
                    )
                }
            }

            WaveformBars(
                levels = levels,
                progress = {
                    tick.longValue
                    if (audio.isCurrent(url)) audio.liveProgress else 0.0
                },
                played = p.accentOnNight,
                unplayed = Color.White.copy(alpha = 0.22f),
                barWidth = 3.5.dp,
                onSeek = { f -> if (audio.isCurrent(url)) audio.seek(f) },
                modifier = Modifier.fillMaxWidth().height(44.dp),
            )

            AnimatedVisibility(
                isCurrent,
                enter = scaleIn(Motion.snappy()) + fadeIn(Motion.snappy()),
                exit = scaleOut(Motion.snappy()) + fadeOut(Motion.snappy()),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    PressScaleButton(
                        onClick = {
                            audio.rate = nextRate(audio.rate)
                            Haptics.select()
                        },
                        modifier = Modifier
                            .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
                            .semantics { stateDescription = rateValue(audio.rate) },
                        contentDescription = L("Playback speed"),
                    ) {
                        Text(
                            rateLabel(audio.rate),
                            Modifier
                                .background(Color.White.copy(alpha = 0.14f), CircleShape)
                                .defaultMinSize(minHeight = 32.dp)
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                            style = TextStyles.footnote.bold.monospacedDigits,
                            color = Color.White,
                        )
                    }
                }
            }
        }
    }
}

// MARK: - Sports

/**
 * All of someone's sports in one block, with how often they do each. Each sport has its own tone,
 * in the bar and on its disc; shared ones say so.
 */
@Composable
fun SportsWeekBlock(
    profile: Profile,
    /** The viewer, to highlight shared sports. Null on your own profile. */
    me: Profile?,
    modifier: Modifier = Modifier,
) {
    val p = DS.palette
    fun shared(s: Sport) = me?.sports?.any { it.sport == s } ?: false

    /** The sport's own tone, shared by its bar segment and its disc. */
    fun tone(i: Int) = p.sportTones[i % p.sportTones.size]
    val sessionsPerWeek = profile.sports.sumOf { it.perWeek }

    NightBlock(modifier.fillMaxWidth()) {
        Column(Modifier.padding(DS.Space.xl), verticalArrangement = Arrangement.spacedBy(DS.Space.xl)) {
            Column(verticalArrangement = Arrangement.spacedBy(DS.Space.xs)) {
                Text(
                    if (me == null) L("How you move") else L("How %s moves", profile.name),
                    Modifier.semantics { heading() },
                    style = display(26f),
                    color = Color.White,
                )
                Text(
                    if (sessionsPerWeek == 1) L("About 1 session a week") else L("About %d sessions a week", sessionsPerWeek),
                    style = TextStyles.subheadline.semibold,
                    color = Color.White.copy(alpha = 0.6f),
                )
            }

            // The week split by sport: one segment per sport, sized by how often.
            Row(
                Modifier.fillMaxWidth().height(10.dp).clearAndSetSemantics { },
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                profile.sports.forEachIndexed { i, entry ->
                    val segment = if (entry.perWeek > 0) Modifier.weight(entry.perWeek.toFloat()) else Modifier.width(0.dp)
                    Box(segment.height(10.dp).background(tone(i), CircleShape))
                }
            }

            Column {
                profile.sports.forEachIndexed { i, entry ->
                    val both = shared(entry.sport)
                    val name = entry.sport.displayName
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = DS.Space.md)
                            .clearAndSetSemantics {
                                contentDescription = if (both) L("%s, %s, you do it too", name, entry.perWeekText) else "$name, ${entry.perWeekText}"
                            },
                        horizontalArrangement = Arrangement.spacedBy(DS.Space.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Same tone as its segment in the bar above: the bar reads as a legend.
                        Box(Modifier.size(38.dp).background(tone(i), CircleShape), contentAlignment = Alignment.Center) {
                            DrafftIcon(entry.sport.symbol, size = symbol(15f), tint = p.night)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(name, style = TextStyles.headline, color = Color.White)
                            if (both) {
                                Text(L("You do it too"), style = TextStyles.caption.bold, color = Color.White.copy(alpha = 0.6f))
                            }
                        }
                        Spacer(Modifier.width(DS.Space.sm))
                        // Fixed-width, right-aligned column so every row's number lines up.
                        Text(
                            L("%d×", entry.perWeek),
                            Modifier.width(52.dp),
                            style = display(28f).monospacedDigits.copy(textAlign = TextAlign.End),
                            color = Color.White,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    if (i < profile.sports.size - 1) {
                        Box(
                            Modifier
                                .padding(start = 50.dp)
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(Color.White.copy(alpha = 0.08f)),
                        )
                    }
                }
            }
        }
    }
}

// MARK: - Goal

/** Lime block: the goal set in display type. */
@Composable
fun GoalBlock(goal: String, modifier: Modifier = Modifier) {
    val p = DS.palette
    Column(
        modifier
            .fillMaxWidth()
            .draftBlock(p.lime)
            .padding(DS.Space.xl),
        verticalArrangement = Arrangement.spacedBy(DS.Space.lg),
    ) {
        DraftGlyph("flag-2", size = 40.dp, fill = p.onLimeWash, glyph = p.onLime)
        Column(verticalArrangement = Arrangement.spacedBy(DS.Space.xs)) {
            Text(L("Training for"), style = TextStyles.subheadline.bold, color = p.onLime)
            Text(goal, style = display(32f), color = p.onLime)
        }
    }
}

// MARK: - Vitals

/** Hinge-style quick facts as a loose cluster of pills; sport stays the headline. */
@Composable
fun VitalsStrip(
    profile: Profile,
    showDistance: Boolean = true,
    /** Off on the detail, where the place already sits under the name. */
    showsPlace: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val p = DS.palette
    val items = vitalsItems(profile, showDistance, showsPlace)
    FlowLayout(modifier, spacing = DS.Space.sm) {
        items.forEach { item ->
            val ink = if (item.lead) p.onLime else p.ink
            Row(
                Modifier
                    .background(if (item.lead) p.lime else p.canvas, CircleShape)
                    .defaultMinSize(minHeight = 36.dp)
                    .padding(horizontal = DS.Space.md)
                    .semantics(mergeDescendants = true) { },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (item.symbol != null) DrafftIcon(item.symbol, size = symbol(13f), tint = ink)
                // Tags never wrap.
                Text(item.text, style = TextStyles.subheadline.semibold, color = ink, maxLines = 1, softWrap = false)
            }
        }
    }
}

private data class VitalsItem(val symbol: String?, val text: String, val lead: Boolean = false)

private fun vitalsItems(profile: Profile, showDistance: Boolean, showsPlace: Boolean): List<VitalsItem> {
    val out = mutableListOf<VitalsItem>()
    if (showsPlace && profile.neighborhood.isNotEmpty()) {
        out += VitalsItem(null, profile.neighborhood + if (showDistance) ", ${LocationPrivacy.rounded(profile.distanceKm)}" else "")
    }
    val v = profile.vitals
    if (v != null && v.chronotype.isNotEmpty()) out += VitalsItem("sunrise", Vitals.label(v.chronotype))
    val pronouns = profile.pronouns
    if (!pronouns.isNullOrEmpty()) out += VitalsItem("user-rounded", pronouns)
    if (v != null) {
        if (v.diet.isNotEmpty()) out += VitalsItem("chef-hat", Vitals.label(v.diet))
        if (v.drinks.isNotEmpty()) out += VitalsItem("wineglass", Vitals.label(v.drinks))
        if (v.smokes.isNotEmpty()) out += VitalsItem("forbidden-circle", if (v.smokes == "Never") L("Doesn't smoke") else Vitals.label(v.smokes))
    }
    return out
}

// MARK: - Prompt

/** A written prompt. In Discover it can be liked on its own; the like carries the quoted answer into the match. */
@Composable
fun PromptCard(prompt: ProfilePrompt, onLike: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val p = DS.palette
    // Heart sits in the layout (not an overlay) so the text always keeps a gap from it and its trail.
    Row(
        modifier
            .fillMaxWidth()
            .background(p.canvas, RoundedCornerShape(DS.Radius.xl))
            .padding(DS.Space.xl),
        horizontalArrangement = Arrangement.spacedBy(DS.Space.lg),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DS.Space.md)) {
            Text(prompt.questionText, style = TextStyles.subheadline.semibold, color = p.body)
            Text(prompt.answer, style = displayBold(26f), color = p.ink)
        }
        if (onLike != null) {
            // Room for the trail.
            LikeHeartButton(label = L("Like this answer"), action = onLike, modifier = Modifier.padding(start = 14.dp))
        }
    }
}

// MARK: - Likes

/** Green like heart with the drafting trail (liking stays green whatever the brand accent). */
@Composable
fun LikeHeartButton(
    label: String = L("Like"),
    size: Dp = 52.dp,
    action: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = DS.palette
    PressScaleButton(
        onClick = {
            Haptics.thump()
            action()
        },
        modifier = modifier
            .draftTrail(CircleShape, color = p.like, step = DpOffset(-size * 0.14f, 0.dp))
            .size(size)
            .background(p.like, CircleShape),
        contentDescription = label,
    ) {
        DrafftIcon("heart", size = symbol(size.value * 0.38f), tint = p.onLike)
    }
}

/** A profile photo that can be liked: heart button, or double-tap with a heart pop. */
@Composable
fun LikablePhoto(
    name: String,
    height: Dp = 400.dp,
    onLike: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val p = DS.palette
    val scope = rememberCoroutineScope()
    val pop = remember { Animatable(0f) }

    fun like(onLike: () -> Unit) {
        Haptics.thump()
        scope.launch {
            launch { pop.animateTo(1f, Motion.bouncy()) }
            delay(420)
            pop.animateTo(0f, tween(180, easing = Motion.EaseOut))
        }
        onLike()
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(DS.Radius.xl))
            .then(
                if (onLike != null) {
                    Modifier.pointerInput(onLike) { detectTapGestures(onDoubleTap = { like(onLike) }) }
                } else Modifier,
            ),
    ) {
        Photo(name, Modifier.fillMaxSize())
        DrafftIcon(
            "heart",
            Modifier
                .align(Alignment.Center)
                .graphicsLayer {
                    val v = pop.value
                    scaleX = 0.4f + 0.6f * v
                    scaleY = 0.4f + 0.6f * v
                    alpha = v.coerceIn(0f, 1f)
                }
                .clearAndSetSemantics { },
            size = symbol(96f),
            tint = p.like,
        )
        if (onLike != null) {
            LikeHeartButton(
                label = L("Like this photo"),
                action = { like(onLike) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(DS.Space.lg),
            )
        }
    }
}
