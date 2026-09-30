package so.drafft.app.feature.profile

// Ports Drafft/Features/Profile/IcebreakerCard.swift.

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import so.drafft.core.data.platform.Haptics
import so.drafft.core.model.Icebreaker
import so.drafft.core.model.L
import so.drafft.core.model.MessageContent
import so.drafft.core.model.Profile
import so.drafft.core.ui.components.DrafftButton
import so.drafft.core.ui.components.DrafftButtonKind
import so.drafft.core.ui.components.NightBlock
import so.drafft.core.ui.components.PressScaleButton
import so.drafft.core.ui.components.pressScale
import so.drafft.core.ui.platform.LocalPlatformUi
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.bold
import so.drafft.core.ui.theme.displayBold
import so.drafft.core.ui.theme.heavy
import so.drafft.core.ui.theme.medium
import so.drafft.core.ui.theme.semibold

/** Interactive icebreaker. Playing it produces a ready-made opener the viewer can send. */
@Composable
fun IcebreakerCard(
    profile: Profile,
    /** Called with the opener to send (quote + reply). */
    onSend: ((MessageContent) -> Unit)? = null,
    sendTitle: String = L("Send as opener"),
    modifier: Modifier = Modifier,
) {
    var guess by rememberSaveable(profile.id) { mutableStateOf<Int?>(null) }
    var revealed by rememberSaveable(profile.id) { mutableStateOf(false) }
    var take by rememberSaveable(profile.id) { mutableStateOf<Boolean?>(null) }
    var choice by rememberSaveable(profile.id) { mutableStateOf<Int?>(null) }
    var sent by rememberSaveable(profile.id) { mutableStateOf(false) }
    val p = DS.palette
    val icebreaker = profile.icebreaker
    val opener = opener(icebreaker, guess, revealed, take, choice)

    NightBlock(modifier.fillMaxWidth()) {
        // The kind's sign, oversized behind the top corner and cut by the block's edge: a faint
        // accent tint on night, texture rather than a second title.
        Box(Modifier.matchParentSize().clip(RoundedCornerShape(DS.Radius.xl)).clearAndSetSemantics { }) {
            DrafftIcon(
                icebreaker.kind.symbol,
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 44.dp, y = (-40).dp)
                    .rotate(-14f),
                size = symbol(168f),
                tint = p.accentOnNight.copy(alpha = 0.13f),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .animateContentSize(Motion.bouncy())
                .padding(DS.Space.xl),
            verticalArrangement = Arrangement.spacedBy(DS.Space.lg),
        ) {
            Text(
                title(profile, icebreaker),
                // Clear of the backdrop glyph's densest part.
                Modifier.padding(end = DS.Space.xxl),
                style = TextStyles.headline,
                color = p.accentOnNight,
            )

            when (icebreaker) {
                is Icebreaker.TwoTruths -> TwoTruths(icebreaker.statements, icebreaker.lieIndex, guess) { guess = it }
                is Icebreaker.Joke -> Joke(icebreaker.setup, icebreaker.punchline, revealed) { revealed = true }
                is Icebreaker.HotTake -> HotTake(icebreaker.text, take) { take = it }
                is Icebreaker.ThisOrThat -> ThisOrThat(profile, icebreaker.question, icebreaker.options, icebreaker.pick, choice) { choice = it }
                is Icebreaker.Guess -> Guess(profile, icebreaker.question, icebreaker.options, icebreaker.answer, choice) { choice = it }
            }

            AnimatedVisibility(
                opener != null && onSend != null,
                enter = slideInVertically(Motion.bouncy()) { it / 2 } + fadeIn(Motion.bouncy()),
                exit = slideOutVertically(Motion.bouncy()) { it / 2 } + fadeOut(Motion.bouncy()),
            ) {
                DrafftButton(
                    onClick = {
                        val message = opener ?: return@DrafftButton
                        Haptics.success()
                        sent = true
                        onSend?.invoke(message)
                    },
                    kind = DrafftButtonKind.LIKE,
                    enabled = !sent,
                ) {
                    DrafftIcon(if (sent) "check" else "heart", size = symbol(17f), tint = p.onLike)
                    Text(if (sent) L("Done") else sendTitle, maxLines = 2)
                }
            }
        }
    }
}

private fun title(profile: Profile, icebreaker: Icebreaker): String = when (icebreaker) {
    is Icebreaker.TwoTruths -> L("Two truths, one lie. Spot the lie.")
    is Icebreaker.Joke -> L("%s's best bad joke", profile.name)
    is Icebreaker.HotTake -> L("Hot take. Agree?")
    is Icebreaker.ThisOrThat -> L("This or that? Pick a side.")
    is Icebreaker.Guess -> L("Guess about %s", profile.name)
}

private fun opener(icebreaker: Icebreaker, guess: Int?, revealed: Boolean, take: Boolean?, choice: Int?): MessageContent? =
    when (icebreaker) {
        is Icebreaker.TwoTruths -> guess?.let {
            val right = it == icebreaker.lieIndex
            MessageContent.IcebreakerReply(
                quote = icebreaker.statements[it],
                reply = if (right) {
                    L("Called it: that one's the lie 😏 What's the real story?")
                } else {
                    L("I was SO sure that was the lie. Tell me it's not true")
                },
            )
        }
        is Icebreaker.Joke -> if (!revealed) null else {
            MessageContent.IcebreakerReply(quote = icebreaker.punchline, reply = L("Okay that got a groan AND a laugh. Respect 😂"))
        }
        is Icebreaker.HotTake -> take?.let {
            MessageContent.IcebreakerReply(
                quote = icebreaker.text,
                reply = if (it) L("Fully agree. Finally someone said it.") else L("Strongly disagree, and I'm ready to argue it over a session"),
            )
        }
        is Icebreaker.ThisOrThat -> choice?.let {
            val q = icebreaker.question
            val options = icebreaker.options
            MessageContent.IcebreakerReply(
                quote = if (options.size == 2) L("%s %s or %s", q, options[0], options[1]) else "$q ${options.joinToString(" / ")}",
                reply = if (it == icebreaker.pick) L("%s, obviously. Great minds 🤝", options[it]) else L("Team %s. We need to talk 😄", options[it]),
            )
        }
        is Icebreaker.Guess -> choice?.let {
            val options = icebreaker.options
            val answer = icebreaker.answer
            MessageContent.IcebreakerReply(
                quote = icebreaker.question,
                reply = if (it == answer) {
                    L("Guessed it: %s! Do I win a session?", options[answer])
                } else {
                    L("I said %s… so it's %s? Tell me more", options[it], options[answer])
                },
            )
        }
    }

// MARK: Variants

@Composable
private fun TwoTruths(statements: List<String>, lie: Int, guess: Int?, onGuess: (Int) -> Unit) {
    val p = DS.palette
    Column(verticalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
        statements.forEachIndexed { i, s ->
            val isLie = i == lie
            val picked = guess == i
            // Your pick carries the fill: an accent wash when it was the lie, a light wash when not.
            val fill = when {
                guess == null -> Color.White.copy(alpha = 0.08f)
                picked -> if (isLie) p.selectedOnNight else Color.White.copy(alpha = 0.18f)
                else -> Color.White.copy(alpha = 0.04f)
            }
            val bg by animateColorAsState(fill, Motion.bouncy(), label = "truthFill")
            Row(
                Modifier
                    .fillMaxWidth()
                    .pressScale(
                        onClick = {
                            if (guess == null) {
                                onGuess(i)
                                if (isLie) Haptics.success() else Haptics.warning()
                            }
                        },
                        scale = 0.98f,
                        enabled = guess == null || picked,
                        onClickLabel = if (guess == null) L("Guess this is the lie") else null,
                    )
                    .background(bg, RoundedCornerShape(DS.Radius.lg))
                    .semantics(mergeDescendants = true) {
                        selected = picked
                        if (guess != null) stateDescription = if (isLie) L("This was the lie") else L("This one is true")
                    }
                    .padding(DS.Space.lg),
                horizontalArrangement = Arrangement.spacedBy(DS.Space.md),
                verticalAlignment = Alignment.Top,
            ) {
                Text(s, Modifier.weight(1f), style = TextStyles.body.medium, color = Color.White)
                AnimatedVisibility(guess != null, enter = scaleIn(Motion.bouncy()) + fadeIn(), exit = scaleOut() + fadeOut()) {
                    Text(
                        if (isLie) L("Lie") else L("True"),
                        Modifier
                            .background(if (isLie) p.accentOnNight else Color.White.copy(alpha = 0.14f), CircleShape)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        style = TextStyles.caption.heavy,
                        color = if (isLie) p.onAccentOnNight else Color.White,
                    )
                }
            }
        }
        AnimatedVisibility(guess != null, enter = fadeIn(Motion.bouncy()), exit = fadeOut()) {
            val right = guess == lie
            Text(
                if (right) L("Nailed it. You read people well.") else L("Nope, that one's true. The lie was a different one."),
                Modifier.fillMaxWidth(),
                style = TextStyles.subheadline.semibold,
                color = if (right) p.accentOnNight else Color.White.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun Joke(setup: String, punchline: String, revealed: Boolean, onReveal: () -> Unit) {
    val p = DS.palette
    // Where the platform can't blur (Android 11 and older), a hidden punchline isn't drawn at all.
    val canBlur = LocalPlatformUi.current.blursEdges
    val blur by animateDpAsState(if (revealed) 0.dp else 9.dp, Motion.bouncy(), label = "punchlineBlur")
    val alpha by animateFloatAsState(if (revealed) 1f else if (canBlur) 0.6f else 0f, Motion.bouncy(), label = "punchlineAlpha")
    val chipAlpha by animateFloatAsState(if (revealed) 0f else 1f, Motion.gentle(), label = "punchlineChip")
    Column(verticalArrangement = Arrangement.spacedBy(DS.Space.md)) {
        Text(setup, style = displayBold(24f), color = Color.White)
        Box(
            Modifier
                .fillMaxWidth()
                .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) {
                    if (!revealed) {
                        Haptics.thump()
                        onReveal()
                    }
                }
                .padding(vertical = DS.Space.xs)
                .clearAndSetSemantics {
                    contentDescription = if (revealed) punchline else L("Reveal punchline")
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                punchline,
                Modifier
                    .fillMaxWidth()
                    .blur(blur)
                    .graphicsLayer { this.alpha = alpha.coerceIn(0f, 1f) },
                style = TextStyles.title3.semibold,
                color = p.accentOnNight,
            )
            if (chipAlpha > 0f) {
                Row(
                    Modifier
                        .graphicsLayer { this.alpha = chipAlpha }
                        .background(Color.White.copy(alpha = 0.14f), CircleShape)
                        .defaultMinSize(minHeight = 36.dp)
                        .padding(horizontal = DS.Space.md),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DrafftIcon("mask-happy", size = symbol(15f), tint = Color.White)
                    Text(L("Tap for the punchline"), style = TextStyles.subheadline.bold, color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun ThisOrThat(profile: Profile, q: String, options: List<String>, pick: Int, choice: Int?, onChoose: (Int) -> Unit) {
    val p = DS.palette
    Column(verticalArrangement = Arrangement.spacedBy(DS.Space.lg)) {
        Text(q, style = displayBold(24f), color = Color.White)
        Row(horizontalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
            options.forEachIndexed { i, option ->
                val on = choice == i
                val theirs = choice != null && i == pick
                // Yours: solid accent. Theirs (when different): an accent wash. No frames.
                val bg by animateColorAsState(
                    if (on) p.accentOnNight else if (theirs) p.selectedOnNight else Color.White.copy(alpha = 0.1f),
                    Motion.bouncy(), label = "sideFill",
                )
                val fg by animateColorAsState(if (on) p.onAccentOnNight else Color.White, Motion.bouncy(), label = "sideInk")
                PressScaleButton(
                    onClick = {
                        if (choice == null) {
                            Haptics.select()
                            onChoose(i)
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .background(bg, RoundedCornerShape(DS.Radius.lg))
                        .defaultMinSize(minHeight = 64.dp)
                        .semantics { selected = on },
                    scale = 0.96f,
                    enabled = choice == null || on,
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = DS.Space.sm),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(option, style = TextStyles.headline.copy(textAlign = TextAlign.Center), color = fg)
                        AnimatedVisibility(theirs, enter = scaleIn(Motion.bouncy()) + fadeIn(), exit = scaleOut() + fadeOut()) {
                            Text(
                                L("%s's pick", profile.name),
                                Modifier.graphicsLayer { alpha = 0.8f },
                                style = TextStyles.caption2.bold,
                                color = fg,
                            )
                        }
                    }
                }
            }
        }
        AnimatedVisibility(choice != null, enter = fadeIn(Motion.bouncy()), exit = fadeOut()) {
            val same = choice == pick
            Text(
                if (same) L("Same pick as %s!", profile.name) else L("%s went the other way.", profile.name),
                style = TextStyles.subheadline.semibold,
                color = if (same) p.accentOnNight else Color.White.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun Guess(profile: Profile, q: String, options: List<String>, answer: Int, choice: Int?, onChoose: (Int) -> Unit) {
    val p = DS.palette
    Column(verticalArrangement = Arrangement.spacedBy(DS.Space.md)) {
        Text(q, style = displayBold(24f), color = Color.White)
        options.forEachIndexed { i, option ->
            val picked = choice == i
            val right = i == answer
            val bg by animateColorAsState(
                Color.White.copy(alpha = if (picked) 0.14f else if (choice == null) 0.08f else 0.04f),
                Motion.bouncy(), label = "guessFill",
            )
            PressScaleButton(
                onClick = {
                    if (choice == null) {
                        if (right) Haptics.success() else Haptics.warning()
                        onChoose(i)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bg, RoundedCornerShape(DS.Radius.lg))
                    .semantics { selected = picked },
                scale = 0.98f,
                enabled = choice == null || picked,
            ) {
                Row(Modifier.fillMaxWidth().padding(DS.Space.lg), verticalAlignment = Alignment.CenterVertically) {
                    Text(option, Modifier.weight(1f), style = TextStyles.body.medium, color = Color.White)
                    AnimatedVisibility(
                        choice != null && (right || picked),
                        enter = scaleIn(Motion.bouncy()) + fadeIn(),
                        exit = scaleOut() + fadeOut(),
                    ) {
                        DrafftIcon(
                            if (right) "check-circle" else "close-circle",
                            size = symbol(17f),
                            tint = if (right) p.accentOnNight else Color.White.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }
        AnimatedVisibility(choice != null, enter = fadeIn(Motion.bouncy()), exit = fadeOut()) {
            val won = choice == answer
            Text(
                if (won) L("Right! You know %s already.", profile.name) else L("Not quite. Now you know."),
                style = TextStyles.subheadline.semibold,
                color = if (won) p.accentOnNight else Color.White.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun HotTake(text: String, take: Boolean?, onTake: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(DS.Space.lg)) {
        Text(L("“%s”", text), style = displayBold(24f), color = Color.White)
        Row(horizontalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
            VoteButton(L("Agree"), "like", on = take == true, Modifier.weight(1f)) { onTake(true) }
            VoteButton(L("Disagree"), "dislike", on = take == false, Modifier.weight(1f)) { onTake(false) }
        }
    }
}

@Composable
private fun VoteButton(label: String, icon: String, on: Boolean, modifier: Modifier, onVote: () -> Unit) {
    val p = DS.palette
    val bg by animateColorAsState(if (on) p.accentOnNight else Color.White.copy(alpha = 0.1f), Motion.bouncy(), label = "voteFill")
    val fg by animateColorAsState(if (on) p.onAccentOnNight else Color.White, Motion.bouncy(), label = "voteInk")
    PressScaleButton(
        onClick = {
            Haptics.select()
            onVote()
        },
        modifier = modifier
            .background(bg, RoundedCornerShape(DS.Radius.lg))
            .defaultMinSize(minHeight = 48.dp)
            .semantics { selected = on },
        scale = 0.96f,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            DrafftIcon(icon, size = symbol(15f), tint = fg)
            Text(label, style = TextStyles.subheadline.bold, color = fg)
        }
    }
}
