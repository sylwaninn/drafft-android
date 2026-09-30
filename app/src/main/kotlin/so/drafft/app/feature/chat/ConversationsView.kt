package so.drafft.app.feature.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import so.drafft.core.data.AppModel
import so.drafft.core.data.platform.Haptics
import so.drafft.core.model.Conversation
import so.drafft.core.model.DateText
import so.drafft.core.model.L
import so.drafft.core.model.Message
import so.drafft.core.model.MessageContent
import so.drafft.core.ui.LocalAppModel
import so.drafft.core.ui.components.Avatar
import so.drafft.core.ui.components.DrafftButton
import so.drafft.core.ui.components.EmptyStateArt
import so.drafft.core.ui.components.EmptyStateView
import so.drafft.core.ui.components.HidesTabBar
import so.drafft.core.ui.components.LocalTabBarInset
import so.drafft.core.ui.components.PressScaleButton
import so.drafft.core.ui.components.TabHeader
import so.drafft.core.ui.components.TabTitle
import so.drafft.core.ui.components.TopBar
import so.drafft.core.ui.components.trackingScrollOffset
import so.drafft.core.ui.navigation.LocalNavStack
import so.drafft.core.ui.navigation.NavStackHost
import so.drafft.core.ui.navigation.rememberNavStack
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.LocalReduceMotion
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.bold
import so.drafft.core.ui.theme.monospacedDigits
import so.drafft.core.ui.theme.semibold
import so.drafft.core.ui.theme.weight
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.launch

// Port of Drafft/Features/Chat/ConversationsView.swift.

/** The Chats tab's root in its stack. */
private object ConversationsRoot

/**
 * Chats tab: new matches in a row, then the conversations. Chats open in this tab's own stack
 * ([ChatRoute]); the tab bar hides while one is pushed and comes back as soon as Back begins.
 */
@Composable
fun ConversationsView(modifier: Modifier = Modifier) {
    val app = LocalAppModel.current
    val stack = rememberNavStack(ConversationsRoot)
    // Decided here, on the path: it flips back to visible as soon as Back begins.
    if (stack.canPop) HidesTabBar()

    // A chat asked for from outside (match screen, banner, notification): shown on top of the list.
    LaunchedEffect(app.chatRequest) {
        val id = app.chatRequest ?: return@LaunchedEffect
        if ((stack.top as? ChatRoute)?.chatID != id) stack.reset(ChatRoute(id))
        app.chatRequest = null
    }

    NavStackHost(stack, modifier) { route ->
        when (route) {
            is ChatRoute -> ChatView(conversationID = route.chatID, focusSession = route.sessionID)
            else -> ConversationList()
        }
    }
}

/** The list's own side margin (inset grouped, iPhone), where its cards start. */
private val Edge = 20.dp

@Composable
private fun ConversationList() {
    val app = LocalAppModel.current
    val stack = LocalNavStack.current
    val list = rememberLazyListState()
    val offset by list.trackingScrollOffset()
    var query by rememberSaveable { mutableStateOf("") }

    val newMatches = app.conversations.filter { it.messages.isEmpty() }
    val threads = app.conversations
        .filter { it.messages.isNotEmpty() }
        .filter { query.isEmpty() || it.profile.name.contains(query, ignoreCase = true) }
    val open: (String) -> Unit = { stack.push(ChatRoute(it)) }

    Box(Modifier.fillMaxSize().background(DS.palette.canvasSoft)) {
        TopBar(
            scroll = list,
            bar = {
                TabHeader(
                    offset = { offset },
                    search = query,
                    onSearchChange = { query = it },
                    searchPrompt = L("Search chats"),
                ) { TabTitle(L("Chats")) }
            },
        ) { padding ->
            LazyColumn(
                Modifier.fillMaxSize(),
                state = list,
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding() + DS.Space.xs,
                    bottom = padding.calculateBottomPadding() + LocalTabBarInset.current + DS.Space.lg,
                ),
            ) {
                if (newMatches.isNotEmpty() && query.isEmpty()) {
                    item(key = "newMatches") {
                        NewMatchesRow(newMatches, open, Modifier.animateItem().padding(bottom = DS.Space.lg))
                    }
                }
                if (threads.isEmpty() && query.isEmpty() && newMatches.isNotEmpty()) {
                    item(key = "noChats") {
                        NoChatsYet(Modifier.animateItem())
                    }
                }
                itemsIndexed(threads, key = { _, c -> c.id }) { i, c ->
                    val first = i == 0
                    val last = i == threads.lastIndex
                    val r = DS.Radius.xl
                    val shape = RoundedCornerShape(
                        topStart = if (first) r else 0.dp,
                        topEnd = if (first) r else 0.dp,
                        bottomStart = if (last) r else 0.dp,
                        bottomEnd = if (last) r else 0.dp,
                    )
                    SwipeActionsRow(
                        shape = shape,
                        leading = SwipeAction(
                            title = if (c.isUnread) L("Read") else L("Unread"),
                            symbol = if (c.isUnread) "envelope.open" else "envelope.badge",
                            tint = DS.palette.night,
                        ) { if (c.isUnread) app.markRead(c.id) else app.markUnread(c.id) },
                        // Stays dark in dark mode too, so the white label keeps its contrast.
                        trailing = SwipeAction(
                            title = if (c.muted) L("Unmute") else L("Mute"),
                            symbol = if (c.muted) "bell" else "bell.slash",
                            tint = DS.palette.nightRaised,
                        ) { app.toggleMute(c.id) },
                        onTap = { open(c.id) },
                        modifier = Modifier.animateItem().padding(horizontal = Edge),
                    ) {
                        Box {
                            ConversationRow(c, Modifier.padding(start = DS.Space.lg, end = DS.Space.md, top = 11.dp, bottom = 11.dp))
                            if (!last) {
                                // The list's separator, from the text's leading edge.
                                Box(
                                    Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(start = DS.Space.lg + 56.dp + DS.Space.md)
                                        .fillMaxWidth()
                                        .height(0.5.dp)
                                        .background(DS.palette.hairline),
                                )
                            }
                        }
                    }
                }
            }
        }

        // A search with no result: two words in the middle of what's visible (between the header and
        // the keyboard), on the page itself.
        if (app.conversations.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyStateView(
                    art = EmptyStateArt.chats,
                    title = L("No chats yet"),
                    message = L("Match with someone on Discover to start chatting."),
                ) {
                    DrafftButton(L("Back to Discover"), onClick = { app.tab = AppModel.Tab.DISCOVER }, fullWidth = false)
                }
            }
        } else {
            AnimatedVisibility(
                visible = threads.isEmpty() && query.isNotEmpty(),
                modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime),
                enter = fadeIn(Motion.gentle()),
                exit = fadeOut(Motion.gentle()),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(L("No results"), style = TextStyles.body, color = DS.palette.body)
                }
            }
        }
    }
}

@Composable
private fun NewMatchesRow(newMatches: List<Conversation>, open: (String) -> Unit, modifier: Modifier = Modifier) {
    // Edge to edge: the row scrolls to the screen's edges, and the title sits on the same margin as
    // the avatars, so the two always line up.
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
        Text(
            L("New matches"),
            Modifier
                .padding(horizontal = Edge)
                .semantics { heading() },
            style = TextStyles.subheadline.bold,
            color = DS.palette.body,
        )
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Edge, vertical = DS.Space.xs),
            horizontalArrangement = Arrangement.spacedBy(DS.Space.sm),
            verticalAlignment = Alignment.Top,
        ) {
            newMatches.forEach { c ->
                PressScaleButton(
                    onClick = { open(c.id) },
                    contentDescription = L("New match, %s. Start chatting", c.profile.name),
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(DS.Space.xs + 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Avatar(c.profile.portrait, size = 68.dp)
                        // A name is the person's own: two lines, then cut.
                        Text(
                            c.profile.name,
                            Modifier.width(72.dp),
                            style = TextStyles.footnote.semibold,
                            color = DS.palette.ink,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** Inside the white list card, never loose on the sage page. */
@Composable
private fun NoChatsYet(modifier: Modifier = Modifier) {
    Column(
        modifier
            .padding(horizontal = Edge)
            .fillMaxWidth()
            .background(DS.palette.canvas, RoundedCornerShape(DS.Radius.xl))
            .padding(horizontal = DS.Space.lg, vertical = DS.Space.xxl),
        verticalArrangement = Arrangement.spacedBy(DS.Space.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DrafftIcon("bubble.left.and.bubble.right", size = 48.dp, tint = DS.palette.mute)
        Text(L("No chats yet"), style = TextStyles.title2.bold, color = DS.palette.ink, textAlign = TextAlign.Center)
        Text(L("Say hi to a new match to start chatting."), style = TextStyles.subheadline, color = DS.palette.body, textAlign = TextAlign.Center)
    }
}

// MARK: - Swipe actions

private class SwipeAction(val title: String, val symbol: String, val tint: Color, val run: () -> Unit)

/**
 * A list row with the iPhone's swipe actions: drag right to reveal [leading], left for [trailing];
 * a long swipe runs the action at once. A tap on the row opens it (or closes the revealed action).
 */
@Composable
private fun SwipeActionsRow(
    shape: Shape,
    leading: SwipeAction,
    trailing: SwipeAction,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val actionWidth = with(density) { 80.dp.toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    // The row's width, read when a drag ends (a plain reference, never state).
    val rowWidth = remember { floatArrayOf(1f) }
    val close: () -> Unit = { scope.launch { offset.animateTo(0f, Motion.snappy()) } }
    val run: (SwipeAction) -> Unit = { action ->
        Haptics.tap()
        action.run()
        close()
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .onSizeChanged { rowWidth[0] = it.width.toFloat() }
            .clip(shape)
            .background(DS.palette.canvas),
    ) {
        // The revealed action, as wide as the gap the row leaves (the whole row on a long swipe).
        val shown = offset.value
        if (shown > 0f) {
            ActionTile(leading, Modifier.align(Alignment.CenterStart).width(with(density) { max(shown, actionWidth).toDp() })) { run(leading) }
        } else if (shown < 0f) {
            ActionTile(trailing, Modifier.align(Alignment.CenterEnd).width(with(density) { max(-shown, actionWidth).toDp() })) { run(trailing) }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .background(DS.palette.canvas)
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch { offset.snapTo(offset.value + delta) }
                    },
                    onDragStopped = { velocity ->
                        val x = offset.value
                        val full = rowWidth[0] * 0.6f
                        when {
                            x > full -> run(leading)
                            x < -full -> run(trailing)
                            x > actionWidth / 2 || velocity > 1200f && x > 0 -> offset.animateTo(actionWidth, Motion.snappy())
                            x < -actionWidth / 2 || velocity < -1200f && x < 0 -> offset.animateTo(-actionWidth, Motion.snappy())
                            else -> offset.animateTo(0f, Motion.snappy())
                        }
                    },
                )
                .clickable(remember { MutableInteractionSource() }, indication = null) {
                    if (abs(offset.value) > 1f) close() else onTap()
                },
        ) { content() }
    }
}

@Composable
private fun ActionTile(action: SwipeAction, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .fillMaxHeight()
            .background(action.tint)
            .clickable(onClick = onClick)
            .semantics { contentDescription = action.title },
        verticalArrangement = Arrangement.spacedBy(DS.Space.xs, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DrafftIcon(action.symbol, size = 22.dp, tint = Color.White)
        Text(action.title, style = TextStyles.footnote.semibold, color = Color.White, maxLines = 1)
    }
}

// MARK: - Row

@Composable
fun ConversationRow(convo: Conversation, modifier: Modifier = Modifier) {
    val p = DS.palette
    val muted = L("Muted")
    Row(
        modifier
            .padding(vertical = DS.Space.xs)
            .semantics(mergeDescendants = true) { if (convo.muted) stateDescription = muted },
        horizontalArrangement = Arrangement.spacedBy(DS.Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(convo.profile.portrait, size = 56.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(DS.Space.xs)) {
                Text(convo.profile.name, Modifier.weight(1f, fill = false).alignByBaseline(), style = TextStyles.headline, color = p.ink)
                if (convo.muted) {
                    // The row's value says it.
                    DrafftIcon("bell.slash", Modifier.alignByBaseline().clearAndSetSemantics { }, size = 15.dp, tint = p.body)
                }
                Spacer(Modifier.weight(1f))
                convo.lastMessage?.date?.let { d ->
                    Text(
                        d.chatStamp,
                        Modifier.alignByBaseline(),
                        style = TextStyles.footnote.weight(if (convo.isUnread) FontWeight.SemiBold else FontWeight.Normal),
                        color = if (convo.isUnread) p.ink else p.mute,
                    )
                }
            }
            Row(verticalAlignment = Alignment.Top) {
                val style = TextStyles.subheadline.weight(if (convo.isUnread) FontWeight.SemiBold else FontWeight.Normal)
                Box(Modifier.weight(1f)) {
                    if (convo.isTyping) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            // The word says it.
                            TypingDots(color = p.accentInk, size = 5.dp, modifier = Modifier.clearAndSetSemantics { })
                            Text(L("Typing"), style = style, color = p.accentInk)
                        }
                    } else {
                        convo.lastMessage?.let { m -> Preview(m, convo.isUnread, style) }
                    }
                }
                Spacer(Modifier.width(DS.Space.sm))
                if (convo.unread > 0) {
                    val unreadLabel = L("%d unread", convo.unread)
                    AnimatedContent(
                        targetState = convo.unread,
                        transitionSpec = { fadeIn(Motion.snappy()).togetherWith(fadeOut(Motion.snappy())) },
                        label = "unread",
                        modifier = Modifier.clearAndSetSemantics { contentDescription = unreadLabel },
                    ) { n ->
                        Box(
                            Modifier
                                .defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)
                                // Muted chats get a quiet badge instead of the accent.
                                .background(if (convo.muted) p.ink.copy(alpha = 0.12f) else p.lime, CircleShape)
                                .padding(horizontal = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "$n",
                                style = TextStyles.caption.bold.monospacedDigits,
                                color = if (convo.muted) p.ink else p.onLime,
                            )
                        }
                    }
                } else if (convo.markedUnread) {
                    // Marked by hand: a dot, the badge's height, no count.
                    val marked = L("Marked as unread")
                    Box(
                        Modifier
                            .size(22.dp)
                            .background(if (convo.muted) p.ink.copy(alpha = 0.12f) else p.lime, CircleShape)
                            .semantics { contentDescription = marked },
                    )
                }
            }
        }
    }
}

/** Grey "You: ", then the content icon, then the text: one text so it wraps as a paragraph. */
@Composable
private fun Preview(m: Message, unread: Boolean, style: androidx.compose.ui.text.TextStyle) {
    val p = DS.palette
    val icon = m.previewIcon
    val text = buildAnnotatedString {
        if (m.fromMe) withStyle(SpanStyle(color = p.mute)) { append(L("You: ")) }
        if (icon != null) {
            appendInlineContent("icon", "")
            append(" ")
        }
        withStyle(SpanStyle(color = if (unread) p.ink else p.body)) { append(m.previewText) }
    }
    val inline = if (icon == null) emptyMap() else mapOf(
        "icon" to InlineTextContent(Placeholder(1.1.em, 1.em, PlaceholderVerticalAlign.TextCenter)) {
            DrafftIcon(icon, Modifier.fillMaxSize(), tint = p.body)
        },
    )
    Text(text, style = style, maxLines = 2, overflow = TextOverflow.Ellipsis, inlineContent = inline)
}

/** The symbol before a message's preview in the list, by content. */
val Message.previewIcon: String?
    get() = when (content) {
        is MessageContent.Photo, is MessageContent.PhotoReply -> "photo"
        is MessageContent.Video -> "video.fill"
        is MessageContent.Voice -> "waveform"
        is MessageContent.File -> "doc.fill"
        is MessageContent.Session -> "flag.2.crossed" // the Sessions tab icon
        else -> null
    }

/** The time today, "Yesterday", else the weekday (abbreviated), in the app's language. */
val Instant.chatStamp: String
    get() {
        val day = atZone(DateText.zone).toLocalDate()
        val today = LocalDate.now(DateText.zone)
        return when (day) {
            today -> DateText.time(this)
            today.minusDays(1) -> L("Yesterday")
            else -> DateText.format("EEE", this)
        }
    }

/** Three dots rising in turn ("Typing"). Still with Remove animations on. */
@Composable
fun TypingDots(
    color: Color = DS.palette.body,
    size: Dp = 7.dp,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalReduceMotion.current
    val typing = L("Typing")
    val time = remember { mutableFloatStateOf(0f) }
    if (!reduceMotion) {
        LaunchedEffect(Unit) {
            val start = withFrameNanos { it }
            while (true) withFrameNanos { time.floatValue = (it - start) / 1_000_000_000f }
        }
    }
    Row(
        modifier.semantics { contentDescription = typing },
        horizontalArrangement = Arrangement.spacedBy(size * 0.6f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { i ->
            Box(
                Modifier
                    .size(size)
                    // Read at draw time: the dots move without recomposing.
                    .graphicsLayer {
                        val wave = if (reduceMotion) 0f else max(0f, sin(time.floatValue * 6f - i * 0.9f))
                        alpha = if (reduceMotion) 0.7f else 0.35f + 0.65f * wave
                        translationY = -size.toPx() * 0.35f * wave
                    }
                    .background(color, CircleShape),
            )
        }
    }
}
