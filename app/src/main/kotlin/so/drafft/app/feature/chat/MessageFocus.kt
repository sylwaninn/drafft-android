package so.drafft.app.feature.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import so.drafft.core.data.platform.Haptics
import so.drafft.core.model.Conversation
import so.drafft.core.model.L
import so.drafft.core.model.Message
import so.drafft.core.model.MessageContent
import so.drafft.core.ui.LocalAppModel
import so.drafft.core.ui.components.glass
import so.drafft.core.ui.components.pressScale
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.LocalReduceMotion
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.TextStyles
import java.text.BreakIterator
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Port of Drafft/Features/Chat/MessageFocus.swift.

/**
 * A message lifted out of the chat on long press, iMessage-style: the page blurs, the bubble stays
 * exactly where it was, a glass bar of reactions sits above it and the actions below.
 */
data class FocusedMessage(
    val message: Message,
    /** The bubble's frame in the window when it was pressed, in pixels. */
    val frame: Rect,
) {
    val id: String get() = message.id
}

private class FocusAction(val title: String, val icon: String, val destructive: Boolean, val run: () -> Unit)

/** The six quick reactions, in order. */
val FocusReactions = listOf("❤️", "🔥", "😂", "👏", "😮", "💪")

/**
 * Drawn over the whole chat (which blurs itself under it). It fades itself in and out: the presenter
 * removes it without a second fade, and it stops taking touches the moment it starts closing.
 */
@Composable
fun MessageFocusOverlay(
    focus: FocusedMessage,
    convo: Conversation,
    onReact: (String) -> Unit,
    onReply: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = LocalAppModel.current
    val reduceMotion = LocalReduceMotion.current
    val clipboard = LocalClipboardManager.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val message = focus.message
    val mine = message.fromMe
    val shown = remember { Animatable(0f) }
    /** On the way out, a second tap (outside, or on another reaction) does nothing. */
    var closing by remember { mutableStateOf(false) }
    /** "+" in the reaction bar: any emoji from the keyboard. */
    var pickingEmoji by remember { mutableStateOf(false) }
    var origin by remember { mutableStateOf(Offset.Zero) }

    fun close(then: () -> Unit) {
        if (closing) return
        closing = true
        scope.launch {
            launch { shown.animateTo(0f, tween(140, easing = Motion.EaseOut)) }
            delay(if (reduceMotion) 0 else 140)
            then()
        }
    }

    val actions = buildList {
        add(FocusAction(L("Reply"), "arrowshape.turn.up.left", false, onReply))
        (message.content as? MessageContent.Text)?.let { t ->
            add(FocusAction(L("Copy"), "doc.on.doc", false) { clipboard.setText(AnnotatedString(t.text)) })
        }
        if (mine) add(FocusAction(L("Unsend"), "arrow.uturn.backward", true) { app.delete(message.id, convo.id) })
    }

    LaunchedEffect(Unit) {
        Haptics.thump()
        shown.animateTo(1f, if (reduceMotion) tween(150, easing = Motion.EaseOut) else Motion.springOf(0.3, 0.75f))
    }
    // System back closes it, like tapping outside.
    BackHandler { close(onDismiss) }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInWindow() },
    ) {
        val px = { v: Float -> with(density) { v.dp.toPx() } }
        val height = constraints.maxHeight.toFloat()
        val width = constraints.maxWidth.toFloat()
        val safeTop = WindowInsets.systemBars.getTop(density).toFloat()
        val safeBottom = WindowInsets.systemBars.getBottom(density).toFloat()
        val keyboard = WindowInsets.ime.getBottom(density).toFloat()
        val barHeight = px(56f)
        val gap = px(10f)
        // Your own messages take no reaction (the bar is only over theirs), so no room is kept for it.
        val barSpace = if (mine) 0f else barHeight + gap
        val menuHeight = actions.size * px(48f)
        val frame = focus.frame.translate(-origin)
        // Shrink very tall bubbles (session cards) so bar, bubble and menu all fit.
        val room = height - safeTop - safeBottom - barSpace - menuHeight - gap - px(24f)
        val scale = min(1f, room / max(frame.height, 1f))
        val h = frame.height * scale
        val total = barSpace + h + gap + menuHeight
        // Keep the bubble where it was, unless that pushes the bar or menu off screen.
        val resting = min(max(frame.top - barSpace, safeTop + px(12f)), height - safeBottom - px(12f) - total)
        // With the emoji keyboard up, lift so the bar and bubble stay above it.
        val lift = if (keyboard > 0f) keyboard else px(360f)
        val targetTop = if (pickingEmoji) max(safeTop + px(12f), min(resting, height - lift - px(12f) - barSpace - h)) else resting
        val top by animateFloatAsState(targetTop, Motion.springOf(0.35, 0.85f), label = "focusTop")

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = shown.value.coerceIn(0f, 1f) }
                .background(DS.palette.canvasSoft.copy(alpha = 0.35f))
                .background(DS.palette.night.copy(alpha = 0.18f))
                .clickable(remember { MutableInteractionSource() }, indication = null, enabled = !closing, onClickLabel = L("Close")) {
                    close(onDismiss)
                },
        )

        val columnWidth = with(density) { (width - px(DS.Space.md.value) * 2).toDp() }
        Column(
            Modifier
                .offset { IntOffset(px(DS.Space.md.value).roundToInt(), top.roundToInt()) }
                .width(columnWidth),
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!mine) {
                ReactionBar(
                    message = message,
                    shown = { shown.value },
                    pickingEmoji = pickingEmoji,
                    enabled = !closing,
                    onPickEmoji = {
                        Haptics.tap()
                        pickingEmoji = true
                    },
                    onEmojiTyped = { e ->
                        pickingEmoji = false
                        Haptics.select()
                        close { onReact(e) }
                    },
                    onReact = { e ->
                        Haptics.select()
                        close { onReact(e) }
                    },
                )
            }
            val w = with(density) { frame.width.toDp() }
            val fh = with(density) { frame.height.toDp() }
            Box(Modifier.size(w, with(density) { h.toDp() }), contentAlignment = Alignment.TopCenter) {
                MessageRow(
                    message = message,
                    convo = convo,
                    groupedWithNext = true,
                    onOpen = {},
                    presentation = true,
                    modifier = Modifier
                        .requiredSize(w, fh)
                        .graphicsLayer {
                            val s = scale * (1f + 0.02f * shown.value)
                            scaleX = s
                            scaleY = s
                            transformOrigin = TransformOrigin(0.5f, 0f)
                            shadowElevation = 24.dp.toPx() * shown.value.coerceIn(0f, 1f)
                            shape = RoundedCornerShape(20.dp)
                            ambientShadowColor = Color.Black.copy(alpha = 0.18f)
                            spotShadowColor = Color.Black.copy(alpha = 0.18f)
                        }
                        .clearAndSetSemantics { },
                )
            }
            Menu(actions, mine, { shown.value }, enabled = !closing, modifier = Modifier.alpha(if (pickingEmoji) 0f else 1f)) { a ->
                close {
                    a.run()
                    onDismiss()
                }
            }
        }
    }
}

/** Glass capsule of reactions; each pops in a beat after the previous one. */
@Composable
private fun ReactionBar(
    message: Message,
    shown: () -> Float,
    pickingEmoji: Boolean,
    enabled: Boolean,
    onPickEmoji: () -> Unit,
    onEmojiTyped: (String) -> Unit,
    onReact: (String) -> Unit,
) {
    val reduceMotion = LocalReduceMotion.current
    val density = LocalDensity.current
    val emojiStyle = TextStyle(fontSize = with(density) { 28.dp.toSp() })
    val p = DS.palette
    Row(
        Modifier
            .graphicsLayer {
                val v = shown()
                val s = 0.85f + 0.15f * v
                scaleX = s
                scaleY = s
                alpha = v.coerceIn(0f, 1f)
                transformOrigin = TransformOrigin(0f, 1f)
            }
            .height(56.dp)
            .glass(CircleShape)
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FocusReactions.forEachIndexed { i, e ->
            val on = message.reaction == e
            val label = if (on) L("Remove %s reaction", e) else L("React %s", e)
            PopIn(delayMillis = i * 25, reduceMotion = reduceMotion) {
                Box(
                    Modifier
                        .size(44.dp)
                        .pressScale({ onReact(e) }, scale = 0.8f, enabled = enabled)
                        .semantics { contentDescription = label }
                        .background(if (on) p.lime.copy(alpha = 0.9f) else Color.Transparent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text(e, style = emojiStyle) }
            }
        }
        // Current reaction if it's not one of the six, then "+" for any emoji.
        val r = message.reaction
        if (r != null && r !in FocusReactions) {
            val label = L("Remove %s reaction", r)
            Box(
                Modifier
                    .size(44.dp)
                    .background(p.lime.copy(alpha = 0.9f), CircleShape)
                    .clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled) { onReact(r) }
                    .semantics {
                        contentDescription = label
                        role = Role.Button
                    },
                contentAlignment = Alignment.Center,
            ) { Text(r, style = emojiStyle) }
        }
        PopIn(delayMillis = 160, reduceMotion = reduceMotion) {
            val more = L("More emoji")
            Box(
                Modifier
                    .size(44.dp)
                    .pressScale(onPickEmoji, scale = 0.85f, enabled = enabled)
                    .semantics { contentDescription = more },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(40.dp)
                        .background(p.ink.copy(alpha = if (pickingEmoji) 0.14f else 0.07f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { DrafftIcon("plus", size = 22.dp, tint = p.ink) }
            }
        }
        EmojiInput(active = pickingEmoji, onPick = onEmojiTyped)
    }
}

/** Pops its content in (0.3 to 1, a bouncy spring) [delayMillis] after it appears. */
@Composable
private fun PopIn(delayMillis: Int, reduceMotion: Boolean, content: @Composable () -> Unit) {
    val progress = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (reduceMotion) return@LaunchedEffect
        delay(delayMillis.toLong())
        progress.animateTo(1f, Motion.springOf(0.32, 0.6f))
    }
    Box(
        Modifier.graphicsLayer {
            val v = progress.value
            val s = 0.3f + 0.7f * v
            scaleX = s
            scaleY = s
            alpha = v.coerceIn(0f, 1f)
        },
    ) { content() }
}

@Composable
private fun Menu(
    actions: List<FocusAction>,
    mine: Boolean,
    shown: () -> Float,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onAction: (FocusAction) -> Unit,
) {
    val p = DS.palette
    Column(
        modifier
            .graphicsLayer {
                val v = shown()
                val s = 0.85f + 0.15f * v
                scaleX = s
                scaleY = s
                alpha = v.coerceIn(0f, 1f)
                transformOrigin = TransformOrigin(if (mine) 1f else 0f, 0f)
            }
            .width(230.dp)
            .glass(RoundedCornerShape(DS.Radius.xl)),
    ) {
        actions.forEachIndexed { i, a ->
            if (i > 0) {
                Box(
                    Modifier
                        .padding(start = 48.dp)
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(p.hairline),
                )
            }
            val tint = if (a.destructive) p.negative else p.ink
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled, role = Role.Button) { onAction(a) }
                    .padding(horizontal = DS.Space.lg),
                horizontalArrangement = Arrangement.spacedBy(DS.Space.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) { DrafftIcon(a.icon, size = 20.dp, tint = tint) }
                Text(a.title, style = TextStyles.body, color = tint)
            }
        }
    }
}

/**
 * Invisible text field that opens the keyboard and hands back the first emoji typed. Used by the
 * reaction bar's "+". Android can't open straight on the emoji keyboard (the iPhone's field asks for
 * the emoji input mode): the person switches to it in the keyboard.
 */
@Composable
fun EmojiInput(active: Boolean, onPick: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var text by remember { mutableStateOf("") }
    LaunchedEffect(active) {
        if (active) {
            focus.requestFocus()
            keyboard?.show()
        } else {
            keyboard?.hide()
        }
    }
    BasicTextField(
        value = text,
        onValueChange = { typed ->
            val emoji = firstEmoji(typed)
            text = ""
            if (emoji != null) onPick(emoji)
        },
        modifier = Modifier
            .size(1.dp)
            .alpha(0.01f)
            .focusRequester(focus)
            .clearAndSetSemantics { },
        textStyle = TextStyle(color = Color.Transparent),
    )
}

/** The first grapheme of [text] that is an emoji, whole (skin tones and joined sequences included). */
private fun firstEmoji(text: String): String? {
    val it = BreakIterator.getCharacterInstance()
    it.setText(text)
    var start = it.first()
    var end = it.next()
    while (end != BreakIterator.DONE) {
        val g = text.substring(start, end)
        if (g.codePoints().anyMatch(::isEmoji)) return g
        start = end
        end = it.next()
    }
    return null
}

private fun isEmoji(cp: Int): Boolean =
    cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || (cp > 0x238C && Character.getType(cp) == Character.OTHER_SYMBOL.toInt())
