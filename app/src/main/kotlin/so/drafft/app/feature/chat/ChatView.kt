package so.drafft.app.feature.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import org.koin.compose.koinInject
import so.drafft.app.feature.me.SessionSafetySheet
import so.drafft.app.feature.profile.ProfileDetailMode
import so.drafft.app.feature.profile.ProfileDetailView
import so.drafft.app.feature.profile.ProposeSessionSheet
import so.drafft.app.feature.profile.ReportSheet
import so.drafft.app.feature.profile.VoicePlayer
import so.drafft.core.data.audio.AudioPlayback
import so.drafft.core.data.chat.ChatService
import so.drafft.core.data.platform.ForegroundReturns
import so.drafft.core.data.platform.Haptics
import so.drafft.core.data.sessions.SessionStore
import so.drafft.core.data.sessions.from
import so.drafft.core.model.Conversation
import so.drafft.core.model.DateText
import so.drafft.core.model.DeliveryState
import so.drafft.core.model.L
import so.drafft.core.model.Message
import so.drafft.core.model.MessageContent
import so.drafft.core.model.Profile
import so.drafft.core.model.SessionProposal
import so.drafft.core.model.appLocale
import so.drafft.core.model.clock
import so.drafft.core.ui.LocalAppModel
import so.drafft.core.ui.components.Avatar
import so.drafft.core.ui.components.DrafftSheet
import so.drafft.core.ui.components.EdgeBars
import so.drafft.core.ui.components.FullScreenCover
import so.drafft.core.ui.components.LocalTabIsCurrent
import so.drafft.core.ui.components.GlassCircleButton
import so.drafft.core.ui.components.MessageImage
import so.drafft.core.ui.components.MessagePhoto
import so.drafft.core.ui.components.Photo
import so.drafft.core.ui.components.PressScaleButton
import so.drafft.core.ui.components.glass
import so.drafft.core.ui.components.pressScale
import so.drafft.core.ui.navigation.LocalNavStack
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.LocalDarkTheme
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.Palette
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.bold
import so.drafft.core.ui.theme.monospacedDigits
import so.drafft.core.ui.theme.semibold
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// Port of Drafft/Features/Chat/ChatView.swift.

/** Opening a chat, on a given session card when [sessionID] is set (from the Sessions tab). */
data class ChatRoute(val chatID: String, val sessionID: UUID? = null)

/** How far from the end, in dp, before the jump-to-latest button shows. */
private const val JUMP_BUTTON_DISTANCE = 240f

/**
 * Scroll bookkeeping for a chat. A plain reference, not observed: it changes on every scroll frame
 * and must never re-render the thread.
 */
private class ScrollMemo {
    /** Follow the end of the thread (true on open, false once the person scrolls up). */
    var stick = true

    /** The initial layout has settled: later pins animate. */
    var settled = false

    /** The lowest message on screen, kept while the app is away from the foreground. */
    var saved: String? = null

    /** Left the foreground and not back yet. */
    var away = false

    /** A finger is on the thread. */
    var touching = false

    /** A scroll (drag, fling or animation) was running. */
    var scrolling = false

    /** What was last revealed on arrival ("bottom" or a session ID). */
    var revealed: String? = null

    /** The newest message already handled (their new one is followed or counted once). */
    var lastID: String? = null

    /** Messages already on screen when the chat opened: only later ones animate in. */
    var known: MutableSet<String>? = null

    /** Downward drag since the finger landed, for putting the keyboard away. */
    var pulled = 0f

    /** The bars' heights over the thread (nav bar, composer), in pixels. */
    var insetTop = 0f
    var insetBottom = 0f

    /** Where the scrolled content and each message sit, read on demand (never state). */
    var content: LayoutCoordinates? = null
    val rows = HashMap<String, LayoutCoordinates>()
}

/** A "Meet safely" sheet asked for a confirmed time. */
private data class SafetyRequest(val session: SessionProposal, val date: Instant, val id: UUID = UUID.randomUUID())

@Composable
fun ChatView(
    conversationID: String,
    /** Scroll to this session's card and flash it (from the Sessions tab). */
    focusSession: UUID? = null,
    modifier: Modifier = Modifier,
) {
    val app = LocalAppModel.current
    val chat = koinInject<ChatService>()
    val playback = koinInject<AudioPlayback>()
    val stack = LocalNavStack.current
    val convo = app.conversation(conversationID)
    var draft by rememberSaveable(conversationID) { mutableStateOf("") }

    // Open only while its tab is the one on screen (SwiftUI's onAppear/onDisappear on a tab switch):
    // left open in a tab behind, new messages must still count as unread.
    val onScreen = LocalTabIsCurrent.current
    DisposableEffect(conversationID, onScreen) {
        if (!onScreen) return@DisposableEffect onDispose { }
        app.openChatID = conversationID
        chat.open(conversationID)
        app.markRead(conversationID)
        onDispose {
            if (app.openChatID == conversationID) app.openChatID = null
            chat.close(conversationID)
            playback.stop()
        }
    }
    // Typing: shown to the other person while there's a draft (stops when it's sent or cleared).
    LaunchedEffect(conversationID) {
        snapshotFlow { draft }.drop(1).collect { chat.typing(conversationID, it) }
    }

    if (convo == null) {
        Box(modifier.fillMaxSize().background(DS.palette.canvasSoft)) {
            GlassCircleButton(
                "chevron.left",
                onClick = { stack.pop() },
                modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(DS.Space.lg),
                contentDescription = L("Back"),
            )
            Column(
                Modifier.align(Alignment.Center),
                verticalArrangement = Arrangement.spacedBy(DS.Space.md),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                DrafftIcon("bubble.left", size = 48.dp, tint = DS.palette.mute)
                Text(L("Chat not found"), style = TextStyles.title2.bold, color = DS.palette.ink)
            }
        }
        return
    }
    ChatContent(convo, focusSession, draft, { draft = it }, modifier)
}

@Composable
private fun ChatContent(
    convo: Conversation,
    focusSession: UUID?,
    draft: String,
    onDraftChange: (String) -> Unit,
    modifier: Modifier,
) {
    val app = LocalAppModel.current
    val chat = koinInject<ChatService>()
    val stack = LocalNavStack.current
    val density = LocalDensity.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val conversationID = convo.id
    val current by rememberUpdatedState(convo)

    var viewer by remember { mutableStateOf<MediaItem?>(null) }
    var showProfile by remember { mutableStateOf(false) }
    var proposing by remember { mutableStateOf(false) }
    var replyingTo by remember { mutableStateOf<Message?>(null) }
    var focused by remember { mutableStateOf<FocusedMessage?>(null) }
    var counterTo by remember { mutableStateOf<SessionProposal?>(null) }
    var showSafety by remember { mutableStateOf(false) }
    var safetyFor by remember { mutableStateOf<SafetyRequest?>(null) }
    var highlighted by remember { mutableStateOf<String?>(null) }
    /** Their messages that arrived while scrolled up: the count on the jump button. */
    var unseen by remember { mutableIntStateOf(0) }
    // Where the thread sits: pinned to the latest message until the person scrolls up, then kept
    // exactly where they left it (back from the background, a photo, a sheet). Opens at the end.
    val scroll = rememberScrollState(Int.MAX_VALUE)
    val memo = remember { ScrollMemo() }
    if (memo.known == null) memo.known = convo.messages.mapTo(HashSet()) { it.id }

    val px = { dp: Float -> with(density) { dp.dp.toPx() } }
    fun atBottom() = scroll.value >= scroll.maxValue - px(24f)

    fun pinToBottom(animated: Boolean) {
        scope.launch {
            if (animated) scroll.animateScrollTo(scroll.maxValue, Motion.snappy()) else scroll.scrollTo(scroll.maxValue)
        }
    }

    /** Where a message sits in the scrolled content: its top and height, in pixels. */
    fun rowBounds(id: String): Pair<Float, Float>? {
        val content = memo.content?.takeIf { it.isAttached } ?: return null
        val row = memo.rows[id]?.takeIf { it.isAttached } ?: return null
        val top = content.localPositionOf(row, Offset.Zero).y
        return top to row.size.height.toFloat()
    }

    /** The lowest message at least 60 % on screen: the anchor back from the background. */
    fun lastVisible(): String? {
        val top = scroll.value + memo.insetTop
        val bottom = scroll.value + scroll.viewportSize - memo.insetBottom
        return current.messages.lastOrNull { m ->
            val (y, h) = rowBounds(m.id) ?: return@lastOrNull false
            val seen = min(bottom, y + h) - max(top, y)
            h > 0 && seen / h >= 0.6f
        }?.id
    }

    suspend fun scrollToMessage(id: String, center: Boolean) {
        val (y, h) = rowBounds(id) ?: return
        // The visible part of the thread: between the nav bar and the composer.
        val viewport = scroll.viewportSize.toFloat()
        val visible = viewport - memo.insetTop - memo.insetBottom
        val target = if (center) y + h / 2 - memo.insetTop - visible / 2 else y + h - viewport + memo.insetBottom
        scroll.scrollTo(target.roundToInt().coerceIn(0, scroll.maxValue))
    }

    /** Back to the list, where the chat shows as unread again (set after leaving, or the open chat would clear it). */
    fun markUnread() {
        Haptics.tap()
        stack.pop()
        app.scope.launch {
            delay(350)
            app.markUnread(conversationID)
        }
    }

    /** Back to the chat list first, then the block lands (the chat disappears from it). */
    fun blockFromChat(person: Profile) {
        Haptics.success()
        stack.pop()
        app.scope.launch {
            delay(350)
            app.block(person)
        }
    }

    // Heights settling, the keyboard, the composer, a reply quote: the bottom stays put. Pinned to the
    // end, the thread follows it; scrolled up, the distance to the end is kept, so whatever was on
    // screen is pushed up, never covered and never scrolled away (WhatsApp).
    LaunchedEffect(scroll) {
        var last = scroll.maxValue
        snapshotFlow { scroll.maxValue }.collect { max ->
            // Not measured yet (the thread opens at the end): nothing to keep.
            val grew = if (last == Int.MAX_VALUE || max == Int.MAX_VALUE) 0 else max - last
            last = max
            if (memo.stick) {
                pinToBottom(animated = memo.settled)
            } else if (grew != 0 && !memo.touching) {
                scroll.dispatchRawDelta(grew.toFloat())
            }
        }
    }
    // Scroll phases: a finger on the thread unpins it; when everything stops, pinned again if at the end.
    LaunchedEffect(scroll) {
        scroll.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    memo.touching = true
                    memo.stick = false
                    memo.pulled = 0f
                }
                is DragInteraction.Stop, is DragInteraction.Cancel -> memo.touching = false
            }
        }
    }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.isScrollInProgress }.collect { running ->
            if (!running && memo.scrolling) memo.stick = atBottom()
            memo.scrolling = running
        }
    }
    // The jump button: only once clearly away from the end (a bit more than a message's height).
    val awayFromEnd by remember { derivedStateOf { scroll.maxValue - scroll.value > px(JUMP_BUTTON_DISTANCE) } }
    val reachedEnd by remember { derivedStateOf { atBottom() } }
    LaunchedEffect(reachedEnd) { if (reachedEnd && unseen > 0) unseen = 0 }

    // Their new message: followed if you're at the end, otherwise counted on the jump button and the
    // thread stays where you're reading.
    val lastID = convo.messages.lastOrNull()?.id
    LaunchedEffect(lastID) {
        val before = memo.lastID
        memo.lastID = lastID
        if (before == null || before == lastID) return@LaunchedEffect
        val last = current.messages.lastOrNull() ?: return@LaunchedEffect
        if (last.fromMe) return@LaunchedEffect
        if (memo.stick && !memo.touching) pinToBottom(animated = memo.settled) else unseen += 1
    }

    // Opened from a notification or a banner: straight to the newest message, even if this chat was
    // already open and scrolled up (and before the background restore puts it back).
    LaunchedEffect(app.latestRequest) {
        if (app.latestRequest?.chatID != conversationID) return@LaunchedEffect
        memo.saved = null
        memo.stick = true
        pinToBottom(animated = false)
    }

    // Back from the background: exactly where the person was, anchored on the lowest message they
    // could see (not a pixel offset: the keyboard may have closed meanwhile). Twice, so a relayout on
    // the way back (keyboard, insets) can't win.
    val foreground = koinInject<ForegroundReturns>()
    LaunchedEffect(foreground) {
        foreground.leaves.collect {
            if (memo.saved == null) memo.saved = if (memo.stick) null else lastVisible()
            memo.away = true
        }
    }
    LaunchedEffect(foreground) {
        foreground.returns.collect {
            if (!memo.away) return@collect
            memo.away = false
            // Replies that came in while away were read on arrival: this chat is on screen.
            app.markRead(conversationID)
            repeat(2) {
                val saved = memo.saved
                if (memo.stick) scroll.scrollTo(scroll.maxValue) else if (saved != null) scrollToMessage(saved, center = false)
                delay(150)
            }
            memo.saved = null
        }
    }

    // Opens on the latest message, or (from Sessions) centres the session card we came for, then
    // flashes it once. Only on arrival: coming back from a photo or a cover keeps the place.
    LaunchedEffect(focusSession) {
        val key = focusSession?.toString() ?: "bottom"
        if (memo.revealed == key) return@LaunchedEffect
        memo.revealed = key
        if (focusSession == null) {
            // Pinned to the latest message; heights settling (images, composer) keep it pinned
            // without animation until the thread has settled.
            memo.stick = true
            pinToBottom(animated = false)
            delay(600)
            // Once more after images, the composer and the push transition have settled.
            if (memo.stick) pinToBottom(animated = false)
            memo.settled = true
            return@LaunchedEffect
        }
        memo.stick = false
        memo.settled = true
        val message = current.messages.firstOrNull { (it.content as? MessageContent.Session)?.proposal?.id == focusSession }
            ?: return@LaunchedEffect
        delay(80) // let the list lay out first
        scrollToMessage(message.id, center = true)
        delay(250)
        highlighted = message.id
        delay(1400)
        highlighted = null
    }

    // At the top of what's loaded: the page before (the bottom anchor keeps the thread in place).
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.value < px(200f) }
            .distinctUntilChanged()
            .drop(1)
            .filter { it }
            .collect { chat.loadOlder(conversationID) }
    }

    // The keyboard goes down when the thread is pulled down (the iPhone's interactive dismissal).
    val pullToHide = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && memo.touching && available.y > 0) {
                    memo.pulled += available.y
                    if (memo.pulled > px(32f)) keyboard?.hide()
                }
                return Offset.Zero
            }
        }
    }

    val focusBlur by animateDpAsState(if (focused != null) 18.dp else 0.dp, Motion.snappy(), label = "focusBlur")

    Box(modifier.fillMaxSize()) {
        EdgeBars(
            scroll = scroll,
            modifier = Modifier
                .fillMaxSize()
                .blur(focusBlur)
                .background(DS.palette.canvasSoft),
            topBar = {
                ChatNavigationBar(
                    convo = convo,
                    onBack = { stack.pop() },
                    onProfile = { showProfile = true },
                    onPropose = { proposing = true },
                    onMarkUnread = ::markUnread,
                    onSafety = { showSafety = true },
                )
            },
            bottomBar = {
                Composer(
                    text = draft,
                    onTextChange = onDraftChange,
                    onSend = { content ->
                        memo.stick = true // your own message always brings you to the end
                        app.send(content, conversationID, replyTo = replyingTo?.id)
                        replyingTo = null
                    },
                    reply = replyingTo?.let { m ->
                        ComposerReply(m.id, if (m.fromMe) "yourself" else convo.profile.name, m.previewText)
                    },
                    onCancelReply = { replyingTo = null },
                )
            },
            navigationEdge = true,
        ) { padding ->
            with(density) {
                memo.insetTop = padding.calculateTopPadding().toPx()
                memo.insetBottom = padding.calculateBottomPadding().toPx()
            }
            Box(Modifier.fillMaxSize()) {
                // A plain column, not a lazy list: a lazy one estimates the height of messages not yet
                // laid out, so scrolling up through older ones made the list jump.
                Column(
                    Modifier
                        .fillMaxSize()
                        .nestedScroll(pullToHide)
                        .verticalScroll(scroll)
                        .onPlaced { memo.content = it }
                        .padding(padding)
                        .padding(horizontal = DS.Space.md)
                        .padding(bottom = DS.Space.sm),
                    verticalArrangement = Arrangement.spacedBy(DS.Space.xs),
                ) {
                    ChatHeaderCard(
                        convo = convo,
                        onTap = { showProfile = true },
                        modifier = Modifier.padding(top = DS.Space.sm, bottom = DS.Space.lg),
                    )

                    val messages = convo.messages
                    messages.forEachIndexed { i, m ->
                        key(m.id) {
                            val prev = messages.getOrNull(i - 1)
                            val next = messages.getOrNull(i + 1)
                            if (prev == null || !sameDay(prev.date, m.date) || Duration.between(prev.date, m.date).seconds > 3600) {
                                DayChip(m.date)
                            }
                            val appears = remember { memo.known?.add(m.id) == true }
                            DisposableEffect(m.id) { onDispose { memo.rows.remove(m.id) } }
                            Appearing(appears, m.fromMe, Modifier.onPlaced { memo.rows[m.id] = it }) {
                                MessageRow(
                                    message = m,
                                    convo = convo,
                                    groupedWithNext = next != null && next.fromMe == m.fromMe &&
                                        Duration.between(m.date, next.date).seconds < 300,
                                    onOpen = { item ->
                                        viewer = item
                                    },
                                    onCounterSession = { counterTo = it },
                                    onSessionSafety = { s, d ->
                                        // After the card has settled into "Confirmed".
                                        scope.launch {
                                            delay(250)
                                            safetyFor = SafetyRequest(s, d)
                                        }
                                    },
                                    onReply = { replyingTo = it },
                                    onRetry = { app.retry(it.id, conversationID) },
                                    onFocus = { message, frame -> focused = FocusedMessage(message, frame) },
                                    hidden = focused?.id == m.id,
                                    highlighted = highlighted == m.id,
                                )
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = convo.isTyping,
                        enter = scaleIn(Motion.snappy(), initialScale = 0.6f, transformOrigin = TransformOrigin(0f, 1f)) + fadeIn(Motion.snappy()),
                        exit = scaleOut(Motion.snappy(), targetScale = 0.6f, transformOrigin = TransformOrigin(0f, 1f)) + fadeOut(Motion.snappy()),
                    ) {
                        Row {
                            Box(
                                Modifier
                                    .height(40.dp)
                                    .background(DS.palette.white, RoundedCornerShape(20.dp))
                                    .padding(horizontal = DS.Space.lg),
                                contentAlignment = Alignment.Center,
                            ) { TypingDots() }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = awayFromEnd,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = DS.Space.md, bottom = padding.calculateBottomPadding() + DS.Space.sm),
                    enter = scaleIn(Motion.snappy(), initialScale = 0.6f) + fadeIn(Motion.snappy()),
                    exit = scaleOut(Motion.snappy(), targetScale = 0.6f) + fadeOut(Motion.snappy()),
                ) {
                    JumpToLatestButton(unseen) {
                        memo.stick = true
                        unseen = 0
                        pinToBottom(animated = true)
                    }
                }
            }
        }

        // Drawn over the chat (which blurs under it), nav bar and composer included.
        focused?.let { f ->
            key(f.id) {
                MessageFocusOverlay(
                    focus = f,
                    convo = convo,
                    onReact = { e ->
                        app.react(e, f.message.id, convo.id)
                        focused = null
                    },
                    onReply = { replyingTo = f.message },
                    onDismiss = { focused = null },
                )
            }
        }
    }

    // The profile, presented over the chat; blocking from it closes the chat behind it.
    DrafftSheet(visible = showProfile, onDismissRequest = { showProfile = false }) {
        ProfileDetailView(profile = convo.profile, mode = ProfileDetailMode.SHEET, onBlocked = { stack.pop() })
    }
    val counter = counterTo
    DrafftSheet(visible = counter != null, onDismissRequest = { counterTo = null }) {
        val original = counter ?: return@DrafftSheet
        ProposeSessionSheet(
            profile = convo.profile,
            me = app.me,
            sendTitle = L("Send new times"),
            counterTo = original,
            onSend = { p -> app.counterSession(original.id, conversationID, p) },
        )
    }
    DrafftSheet(visible = proposing, onDismissRequest = { proposing = false }) {
        ProposeSessionSheet(profile = convo.profile, me = app.me, onSend = { p -> app.proposeSession(p, conversationID) })
    }
    val safety = safetyFor
    DrafftSheet(visible = safety != null, onDismissRequest = { safetyFor = null }) {
        val r = safety ?: return@DrafftSheet
        SessionSafetySheet(session = r.session, date = r.date, partner = convo.profile.name)
    }
    DrafftSheet(visible = showSafety, onDismissRequest = { showSafety = false }) {
        ReportSheet(profile = convo.profile, onDone = { blockFromChat(convo.profile) })
    }
    val shownMedia = viewer
    FullScreenCover(visible = shownMedia != null, onDismissRequest = { viewer = null }) {
        if (shownMedia != null) {
            MediaViewer(items = MediaItem.gallery(convo), start = shownMedia, onDismiss = { viewer = null })
        }
    }
}

private fun sameDay(a: Instant, b: Instant): Boolean =
    a.atZone(DateText.zone).toLocalDate() == b.atZone(DateText.zone).toLocalDate()

/** New messages grow in from their corner (0.85) and fade in; the ones there on arrival just show. */
@Composable
private fun Appearing(appears: Boolean, fromMe: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    val progress = remember { Animatable(if (appears) 0f else 1f) }
    if (appears) LaunchedEffect(Unit) { progress.animateTo(1f, Motion.snappy()) }
    Box(
        modifier.graphicsLayer {
            val v = progress.value
            val s = 0.85f + 0.15f * v
            scaleX = s
            scaleY = s
            alpha = v.coerceIn(0f, 1f)
            transformOrigin = TransformOrigin(if (fromMe) 1f else 0f, 1f)
        },
    ) { content() }
}

/** A separator chip, never loose text on the page. */
@Composable
private fun DayChip(date: Instant) {
    Box(Modifier.fillMaxWidth().padding(vertical = DS.Space.sm), contentAlignment = Alignment.Center) {
        Text(
            date.dayStamp,
            Modifier
                .background(DS.palette.white, CircleShape)
                .padding(horizontal = DS.Space.md, vertical = 6.dp),
            style = TextStyles.footnote.semibold,
            color = DS.palette.body,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** WhatsApp's arrow: back to the newest message, with how many of theirs arrived meanwhile. */
@Composable
private fun JumpToLatestButton(unseen: Int, action: () -> Unit) {
    val label = if (unseen > 0) L("%d new messages, go to the latest", unseen) else L("Go to the latest message")
    Box(
        Modifier
            .size(44.dp)
            .pressScale(action)
            .semantics { contentDescription = label },
    ) {
        Box(Modifier.fillMaxSize().glass(CircleShape), contentAlignment = Alignment.Center) {
            DrafftIcon("chevron.down", size = 20.dp, tint = DS.palette.ink)
        }
        AnimatedVisibility(
            visible = unseen > 0,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp),
            enter = scaleIn(Motion.bouncy()) + fadeIn(Motion.bouncy()),
            exit = scaleOut(Motion.bouncy()) + fadeOut(Motion.bouncy()),
        ) {
            Box(
                Modifier
                    .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
                    .background(DS.palette.lime, CircleShape)
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("$unseen", style = TextStyles.caption2.bold, color = DS.palette.onLime)
            }
        }
    }
}

// MARK: - Header

/**
 * The chat's navigation bar: back, the person (avatar, name, presence; opens their profile), propose
 * a session, and More (mute, mark as unread, then report or block, apart).
 */
@Composable
private fun ChatNavigationBar(
    convo: Conversation,
    onBack: () -> Unit,
    onProfile: () -> Unit,
    onPropose: () -> Unit,
    onMarkUnread: () -> Unit,
    onSafety: () -> Unit,
) {
    val app = LocalAppModel.current
    var menu by remember { mutableStateOf(false) }
    val viewProfile = L("View %s's profile", convo.profile.name)
    // Back on the left, the person in the middle (as centred as the buttons allow, at most 210 wide),
    // the actions on the right, like the iPhone's bar.
    Layout(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .padding(horizontal = DS.Space.lg, vertical = DS.Space.xs),
        content = {
            GlassCircleButton("chevron.left", onBack, contentDescription = L("Back"))
            // No capsule behind: the bar's own blur is the only backdrop, so the text uses the page's inks.
            Row(
                Modifier
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onProfile)
                    .clearAndSetSemantics {
                        contentDescription = viewProfile
                        onClick { onProfile(); true }
                    }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(DS.Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(convo.profile.portrait, size = 32.dp)
                Column {
                    // Long names end with "…": the header never pushes the bar's buttons away (a
                    // person's name, content, not copy).
                    Text(
                        convo.profile.name,
                        style = TextStyles.headline,
                        color = DS.palette.ink,
                        maxLines = 1,
                        // design-lint: allow truncation - a person's name (content, not copy) in the header
                        overflow = TextOverflow.Ellipsis,
                    )
                    PresenceLine(convo)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
                GlassCircleButton("calendar.badge.plus", onPropose, contentDescription = L("Propose a session"))
                Box {
                    // Neutral icons: the menu doesn't take the accent tint.
                    GlassCircleButton("ellipsis", { menu = true }, contentDescription = L("More"))
                    // Profile and sessions are one tap away in the bar: this is about the chat, then
                    // safety, apart.
                    ChatMenu(expanded = menu, onDismiss = { menu = false }) {
                        ChatMenuItem(
                            if (convo.muted) L("Unmute notifications") else L("Mute notifications"),
                            if (convo.muted) "bell" else "bell.slash",
                        ) {
                            menu = false
                            app.toggleMute(convo.id)
                        }
                        ChatMenuItem(L("Mark as unread"), "envelope.badge") {
                            menu = false
                            onMarkUnread()
                        }
                        Box(Modifier.fillMaxWidth().padding(vertical = DS.Space.xs).height(6.dp).background(DS.palette.ink.copy(alpha = 0.05f)))
                        ChatMenuItem(L("Report or block"), "shield.lefthalf.filled", destructive = true) {
                            menu = false
                            onSafety()
                        }
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val gap = DS.Space.sm.roundToPx()
        val back = measurables[0].measure(loose)
        val actions = measurables[2].measure(loose)
        val room = constraints.maxWidth - back.width - actions.width - gap * 2
        val person = measurables[1].measure(loose.copy(maxWidth = max(0, min(210.dp.roundToPx(), room))))
        val height = maxOf(back.height, actions.height, person.height, constraints.minHeight)
        layout(constraints.maxWidth, height) {
            back.placeRelative(0, (height - back.height) / 2)
            actions.placeRelative(constraints.maxWidth - actions.width, (height - actions.height) / 2)
            val centred = (constraints.maxWidth - person.width) / 2
            val x = centred.coerceIn(back.width + gap, max(back.width + gap, constraints.maxWidth - actions.width - gap - person.width))
            person.placeRelative(x, (height - person.height) / 2)
        }
    }
}

/** Under the name in the bar: "Typing…", or "Active now" while they have the app open (nothing otherwise). */
@Composable
private fun PresenceLine(convo: Conversation) {
    if (!convo.isTyping && !convo.online) return
    AnimatedContent(
        targetState = convo.isTyping,
        transitionSpec = { fadeIn(Motion.gentle()).togetherWith(fadeOut(Motion.gentle())) },
        label = "presence",
    ) { typing ->
        Text(
            if (typing) L("Typing…") else L("Active now"),
            style = TextStyles.caption.semibold,
            color = if (typing) DS.palette.accentInk else DS.palette.body,
            maxLines = 1,
        )
    }
}

/** At the top of the thread: who you matched with, and when. A white block: nothing loose on the page. */
@Composable
fun ChatHeaderCard(convo: Conversation, onTap: () -> Unit, modifier: Modifier = Modifier) {
    val chat = koinInject<ChatService>()
    // At the top of what's loaded: the page before (the thread keeps its place, the bottom anchor
    // holds the end).
    LaunchedEffect(convo.id) { chat.loadOlder(convo.id) }
    val hint = L("Opens their profile")
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DS.Radius.xl))
            .background(DS.palette.white)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = hint, onClick = onTap)
            .padding(DS.Space.xl),
        verticalArrangement = Arrangement.spacedBy(DS.Space.md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Avatar(convo.profile.portrait, size = 88.dp, ring = true)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // Names wrap, never truncate.
            Text(
                L("You matched with %s", convo.profile.name),
                style = TextStyles.headline,
                color = DS.palette.ink,
                textAlign = TextAlign.Center,
            )
            Text(
                DateText.relative(convo.matchedAt).capitalizedFirst,
                style = TextStyles.footnote,
                color = DS.palette.body,
                maxLines = 1,
            )
        }
    }
}

/** The first letter upper-cased (in the app's language), the rest as is. */
val String.capitalizedFirst: String
    get() = take(1).uppercase(appLocale) + drop(1)

/** "Today 18:30", "Yesterday 18:30", else "Tuesday 18:30": the separator chips in a thread. */
val Instant.dayStamp: String
    get() {
        val day = atZone(DateText.zone).toLocalDate()
        val today = LocalDate.now(DateText.zone)
        val time = DateText.time(this)
        return when (day) {
            today -> L("Today %s", time)
            today.minusDays(1) -> L("Yesterday %s", time)
            else -> "${DateText.format("EEEE", this)} $time"
        }
    }

// MARK: - Message row

/** How far a bubble follows the finger before a reply is armed, in dp. */
private const val REPLY_THRESHOLD = 56f

@Composable
fun MessageRow(
    message: Message,
    convo: Conversation,
    groupedWithNext: Boolean,
    onOpen: (MediaItem) -> Unit,
    /** Opens the "other times" sheet for an invite. */
    onCounterSession: (SessionProposal) -> Unit = {},
    /** A session time was confirmed (by you) or the safety tips were asked for from its card. */
    onSessionSafety: (SessionProposal, Instant) -> Unit = { _, _ -> },
    onReply: (Message) -> Unit = {},
    /** A message that couldn't be sent, tapped. */
    onRetry: (Message) -> Unit = {},
    /** Long press: lift this bubble into the reactions overlay (its frame in the window). */
    onFocus: (Message, Rect) -> Unit = { _, _ -> },
    /** Hidden in the list while its copy is lifted in the overlay. */
    hidden: Boolean = false,
    /** Render only the bubble (the overlay's copy): no swipe, no long press. */
    presentation: Boolean = false,
    /** The card we came for (from Sessions): an accent wash behind the bubble, fading out. */
    highlighted: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (presentation) {
        BubbleWithReaction(message, convo, groupedWithNext, onOpen, onCounterSession, onSessionSafety, presentation = true, modifier = modifier)
    } else {
        MessageRowContent(message, convo, groupedWithNext, onOpen, onCounterSession, onSessionSafety, onReply, onRetry, onFocus, hidden, highlighted, modifier)
    }
}

@Composable
private fun MessageRowContent(
    message: Message,
    convo: Conversation,
    groupedWithNext: Boolean,
    onOpen: (MediaItem) -> Unit,
    onCounterSession: (SessionProposal) -> Unit,
    onSessionSafety: (SessionProposal, Instant) -> Unit,
    onReply: (Message) -> Unit,
    onRetry: (Message) -> Unit,
    onFocus: (Message, Rect) -> Unit,
    hidden: Boolean,
    highlighted: Boolean,
    modifier: Modifier,
) {
    val mine = message.fromMe
    val p = DS.palette
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val slop = LocalViewConfiguration.current.touchSlop
    val focus by rememberUpdatedState(onFocus)
    val reply by rememberUpdatedState(onReply)
    val currentMessage by rememberUpdatedState(message)
    // Where the bubble sits, read when it's pressed. A plain reference, not state: it changes on every
    // frame of a scroll or a push, and as state it would re-render every row of the chat each frame.
    val frameBox = remember { arrayOfNulls<LayoutCoordinates>(1) }
    // Swipe right to reply, like WhatsApp: the bubble follows, an arrow fills in, release past it.
    val swipe = remember { Animatable(0f) }
    val armed by remember { derivedStateOf { swipe.value >= REPLY_THRESHOLD } }
    val wash by animateFloatAsState(
        if (highlighted) 1f else 0f,
        if (highlighted) Motion.snappy() else tween(500, easing = Motion.EaseOut),
        label = "highlight",
    )
    val quoted = quoted(message, convo)
    val isText = message.content is MessageContent.Text
    val replyLabel = L("Reply")
    val reactLabel = L("React")

    fun lift() {
        val coords = frameBox[0]?.takeIf { it.isAttached } ?: return
        focus(currentMessage, coords.boundsInWindow())
    }

    Box(
        modifier
            .fillMaxWidth()
            .padding(bottom = if (groupedWithNext) 0.dp else DS.Space.sm)
            .replySwipeGesture(
                onChanged = { x ->
                    val crossedBefore = swipe.value >= REPLY_THRESHOLD
                    // Rubber band past the threshold.
                    val next = if (x < REPLY_THRESHOLD) x else REPLY_THRESHOLD + (x - REPLY_THRESHOLD) * 0.25f
                    scope.launch { swipe.snapTo(next) }
                    if (!crossedBefore && next >= REPLY_THRESHOLD) Haptics.select()
                },
                onEnded = {
                    if (swipe.value >= REPLY_THRESHOLD) reply(currentMessage)
                    scope.launch { swipe.animateTo(0f, Motion.snappy()) }
                },
            )
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(replyLabel) { reply(currentMessage); true },
                    CustomAccessibilityAction(reactLabel) { lift(); true },
                )
            },
    ) {
        // The reply arrow, filling in as the bubble moves.
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .graphicsLayer {
                    val s = swipe.value
                    val f = min(1f, s / REPLY_THRESHOLD)
                    translationX = (min(s, REPLY_THRESHOLD) - 44f) * density.density
                    scaleX = 0.5f + 0.5f * f
                    scaleY = 0.5f + 0.5f * f
                    alpha = f.coerceIn(0f, 1f)
                }
                .size(36.dp)
                .background(if (armed) p.lime else p.white, CircleShape)
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            DrafftIcon("arrowshape.turn.up.left.fill", size = 20.dp, tint = if (armed) p.onLime else p.ink)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset((swipe.value * density.density).roundToInt(), 0) },
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (quoted != null && !isText) OuterQuote(quoted, convo)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = if (message.reaction != null) 14.dp else 0.dp),
                horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
                verticalAlignment = Alignment.Bottom,
            ) {
                if (mine) Spacer(Modifier.width(56.dp + DS.Space.sm))
                BubbleWithReaction(
                    message, convo, groupedWithNext, onOpen, onCounterSession, onSessionSafety,
                    presentation = false,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .drawHighlight(p.lime) { wash }
                        .onPlaced { frameBox[0] = it }
                        .alpha(if (hidden) 0f else 1f)
                        // Long press (0.3 s) lifts the bubble; it never takes the tap from buttons inside.
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                var moved = 0f
                                val released = withTimeoutOrNull(300) {
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull true
                                        if (!change.pressed) return@withTimeoutOrNull true
                                        moved += change.positionChange().getDistance()
                                        if (moved > slop || change.isConsumed) return@withTimeoutOrNull true
                                    }
                                    @Suppress("UNREACHABLE_CODE") true
                                }
                                if (released != null) return@awaitEachGesture
                                lift()
                                // The rest of this touch belongs to the lift: nothing under it reacts.
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    event.changes.forEach { it.consume() }
                                    if (event.changes.none { it.pressed }) break
                                }
                            }
                        },
                )
                if (!mine) Spacer(Modifier.width(56.dp + DS.Space.sm))
                if (message.state == DeliveryState.FAILED) {
                    val notSent = L("Not sent. Tap to try again.")
                    Box(
                        Modifier
                            .padding(start = DS.Space.sm)
                            .size(44.dp)
                            .clickable(remember { MutableInteractionSource() }, indication = null) { onRetry(message) }
                            .semantics { contentDescription = notSent },
                        contentAlignment = Alignment.Center,
                    ) {
                        DrafftIcon("exclamationmark.circle.fill", size = 24.dp, tint = p.negative)
                    }
                }
            }
        }
    }
}

/** Flash: an accent wash behind the bubble, not a frame ([alpha] read at draw time). */
private fun Modifier.drawHighlight(color: Color, alpha: () -> Float): Modifier = drawBehind {
    val a = alpha()
    if (a <= 0f) return@drawBehind
    val pad = 6.dp.toPx()
    drawRoundRect(
        color.copy(alpha = 0.28f * a),
        topLeft = Offset(-pad, -pad),
        size = Size(size.width + pad * 2, size.height + pad * 2),
        cornerRadius = CornerRadius((DS.Radius.xl + 6.dp).toPx()),
    )
}

@Composable
private fun BubbleWithReaction(
    message: Message,
    convo: Conversation,
    groupedWithNext: Boolean,
    onOpen: (MediaItem) -> Unit,
    onCounterSession: (SessionProposal) -> Unit,
    onSessionSafety: (SessionProposal, Instant) -> Unit,
    presentation: Boolean,
    modifier: Modifier = Modifier,
) {
    val mine = message.fromMe
    val density = LocalDensity.current
    val shown = remember { arrayOfNulls<String>(1) }
    message.reaction?.let { shown[0] = it }
    Box(modifier) {
        Bubble(message, convo, groupedWithNext, onOpen, onCounterSession, onSessionSafety, presentation)
        AnimatedVisibility(
            visible = message.reaction != null,
            modifier = Modifier
                .align(if (mine) Alignment.BottomStart else Alignment.BottomEnd)
                .offset(x = if (mine) (-10).dp else 10.dp, y = 14.dp),
            enter = scaleIn(Motion.bouncy()) + fadeIn(Motion.bouncy()),
            exit = scaleOut(Motion.bouncy()) + fadeOut(Motion.bouncy()),
        ) {
            val r = shown[0].orEmpty()
            val label = L("Reaction %s", r)
            Text(
                r,
                Modifier
                    .background(DS.palette.white, CircleShape)
                    .border(2.dp, DS.palette.canvasSoft, CircleShape)
                    .padding(5.dp)
                    .semantics { contentDescription = label },
                style = TextStyle(fontSize = with(density) { 14.dp.toSp() }),
            )
        }
    }
}

/** The message this one answers: from the thread, or what the chat service kept of it when it isn't loaded. */
private fun quoted(message: Message, convo: Conversation): Message? {
    val id = message.replyTo ?: return null
    convo.messages.firstOrNull { it.id == id }?.let { return it }
    return message.replyQuote?.let { Message(id = id, content = MessageContent.Text(it.text), fromMe = it.fromMe) }
}

private fun quoteLabel(q: Message, convo: Conversation): String =
    if (q.fromMe) L("In reply to you: %s", q.previewText) else L("In reply to %s: %s", convo.profile.name, q.previewText)

/** Bubble corners: round, with a small tail corner on the last of a group. */
private fun bubbleShape(mine: Boolean, groupedWithNext: Boolean): RoundedCornerShape {
    val big = 20.dp
    val small = 6.dp
    return RoundedCornerShape(
        topStart = big,
        topEnd = big,
        bottomStart = if (!mine && !groupedWithNext) small else big,
        bottomEnd = if (mine && !groupedWithNext) small else big,
    )
}

/**
 * Bubble size for a photo or video: 240 wide, the media's own height, kept between a tall 0.65 and a
 * wide 1.8 ratio so panoramas and very tall shots stay readable.
 */
fun bubbleSize(media: Pair<Double, Double>?): DpSize {
    val width = 240f
    if (media == null || media.first <= 0 || media.second <= 0) return DpSize(width.dp, 300.dp)
    val ratio = (media.first / media.second).coerceIn(0.65, 1.8)
    return DpSize(width.dp, Math.round(width / ratio).toFloat().dp)
}

/** Quote in a bubble: accent bar, name, first lines. On your ink bubble the accent reads as on night. */
@Composable
private fun InnerQuote(q: Message, mine: Boolean, convo: Conversation) {
    val dark = LocalDarkTheme.current
    val p = DS.palette
    // Your bubble is ink: its accent is taken as in light mode (white on the dark ink, graphite on the
    // light ink of dark mode).
    val accent = when {
        mine && !dark -> Palette.Light.accentOnNight
        mine -> Palette.Light.accentInk
        else -> p.accentInk
    }
    val label = quoteLabel(q, convo)
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(if (mine) p.white.copy(alpha = 0.12f) else p.canvasSoft, RoundedCornerShape(14.dp))
            .padding(start = 6.dp, end = DS.Space.sm, top = 6.dp, bottom = 6.dp)
            .clearAndSetSemantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(DS.Space.sm),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(if (mine) accent else p.lime, CircleShape))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(if (q.fromMe) L("You") else convo.profile.name, style = TextStyles.caption.bold, color = accent)
            Text(
                q.previewText,
                style = TextStyles.footnote,
                color = if (mine) p.white.copy(alpha = 0.75f) else p.body,
                maxLines = 2,
                // design-lint: allow truncation - a quoted message (content, not copy)
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The quote above a media bubble that answers a message. */
@Composable
private fun OuterQuote(q: Message, convo: Conversation) {
    val p = DS.palette
    val label = quoteLabel(q, convo)
    Row(
        Modifier
            // Tucked 4 dp into the bubble below.
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, max(0, placeable.height - DS.Space.xs.roundToPx())) { placeable.place(0, 0) }
            }
            .widthIn(max = 260.dp)
            .height(IntrinsicSize.Min)
            .background(p.white.copy(alpha = 0.6f), RoundedCornerShape(DS.Radius.lg))
            .padding(horizontal = DS.Space.md, vertical = DS.Space.sm)
            .clearAndSetSemantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(DS.Space.sm),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(p.lime, CircleShape))
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(if (q.fromMe) L("You") else convo.profile.name, style = TextStyles.caption.bold, color = p.accentInk)
            // design-lint: allow truncation - a quoted message (content, not copy)
            Text(q.previewText, style = TextStyles.footnote, color = p.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Bubble(
    message: Message,
    convo: Conversation,
    groupedWithNext: Boolean,
    onOpen: (MediaItem) -> Unit,
    onCounterSession: (SessionProposal) -> Unit,
    onSessionSafety: (SessionProposal, Instant) -> Unit,
    presentation: Boolean,
) {
    val app = LocalAppModel.current
    val mine = message.fromMe
    val p = DS.palette
    val shape = bubbleShape(mine, groupedWithNext)
    val fill = if (mine) p.ink else p.white
    val ink = if (mine) p.white else p.ink

    when (val content = message.content) {
        is MessageContent.Text -> {
            val q = quoted(message, convo)
            // A reply carries its quote inside the bubble, WhatsApp-style.
            Column(
                Modifier
                    .width(IntrinsicSize.Max)
                    .background(fill, shape)
                    // Double tap: quick ❤️, like Instagram and iMessage. Only on their messages: you don't
                    // react to your own.
                    .then(
                        if (!presentation && !mine) {
                            Modifier.pointerInput(message.id) {
                                detectTapGestures(onDoubleTap = { app.react("❤️", message.id, convo.id) })
                            }
                        } else {
                            Modifier
                        },
                    )
                    .padding(start = 6.dp, end = 6.dp, top = if (q == null) 10.dp else 6.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (q != null) InnerQuote(q, mine, convo)
                Text(content.text, Modifier.padding(horizontal = 8.dp), style = TextStyles.body, color = ink)
            }
        }

        is MessageContent.Photo -> {
            val size = bubbleSize(photoSize(message, content.imageData))
            val data = content.imageData
            val asset = content.asset
            val photo = L("Photo")
            val hint = L("Opens it full screen")
            PressScaleButton(
                onClick = { onOpen(MediaItem(message.id, MediaItem.Kind.Photo(asset, data))) },
                modifier = Modifier
                    .size(size)
                    .clip(RoundedCornerShape(20.dp))
                    .semantics { contentDescription = "$photo. $hint" },
                scale = 0.97f,
            ) {
                // The photo's own proportions (within limits, like WhatsApp).
                when {
                    data != null -> MessagePhoto(message.id, data, Modifier.fillMaxSize())
                    asset != null -> Photo(asset, Modifier.fillMaxSize(), side = size.width)
                    else -> Box(Modifier.fillMaxSize().background(p.white))
                }
            }
        }

        is MessageContent.Video -> {
            val media = message.mediaSize?.let { it.width to it.height }
                ?: content.thumbnail?.let { MessageImage.size(it) }?.let { it.width.toDouble() to it.height.toDouble() }
            val size = bubbleSize(media)
            val label = L("Video, %d seconds", content.duration.toInt())
            val hint = L("Plays it full screen")
            PressScaleButton(
                onClick = { onOpen(MediaItem(message.id, MediaItem.Kind.Video(content.url))) },
                modifier = Modifier.semantics { contentDescription = "$label. $hint" },
                scale = 0.97f,
            ) {
                Box(Modifier.size(size).clip(RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
                    val thumb = content.thumbnail
                    val poster = message.poster
                    when {
                        thumb != null -> MessagePhoto(message.id, thumb, Modifier.fillMaxSize())
                        poster != null -> Photo(poster, Modifier.fillMaxSize(), side = size.width)
                        else -> Box(Modifier.fillMaxSize().background(p.night))
                    }
                    Box(
                        Modifier
                            .size(56.dp)
                            .background(p.accentOnNight, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { DrafftIcon("play.fill", size = 26.dp, tint = p.onAccentOnNight) }
                    Row(
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(10.dp)
                            .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(DS.Space.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DrafftIcon("video.fill", size = 14.dp, tint = Color.White)
                        Text(content.duration.clock, style = TextStyles.caption.bold.monospacedDigits, color = Color.White)
                    }
                }
            }
        }

        is MessageContent.Voice -> VoicePlayer(
            url = content.url,
            duration = content.duration,
            levels = content.levels,
            tint = if (mine) p.white else p.ink,
            track = if (mine) p.white.copy(alpha = 0.3f) else p.ink.copy(alpha = 0.22f),
            buttonFill = if (mine) p.white else p.lime, // your ink bubble: its inverse
            buttonGlyph = if (mine) p.ink else p.onLime,
            showsSpeed = true,
            scrubbable = false, // a sideways drag on a bubble is swipe-to-reply
            modifier = Modifier
                .background(fill, shape)
                .padding(start = 8.dp, end = 14.dp, top = 8.dp, bottom = 8.dp)
                .width(250.dp),
        )

        is MessageContent.File -> PressScaleButton(
            onClick = { content.url?.let { onOpen(MediaItem(message.id, MediaItem.Kind.File(it))) } },
            scale = 0.97f,
        ) {
            Row(
                Modifier
                    .widthIn(max = 260.dp)
                    .background(fill, shape)
                    .padding(DS.Space.md),
                horizontalArrangement = Arrangement.spacedBy(DS.Space.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(width = 44.dp, height = 52.dp)
                        .background(if (mine) p.white else p.lime, RoundedCornerShape(DS.Radius.sm)),
                    contentAlignment = Alignment.Center,
                ) {
                    DrafftIcon(
                        if (content.name.lowercase().endsWith(".pdf")) "doc.richtext.fill" else "doc.fill",
                        size = 26.dp,
                        tint = if (mine) p.ink else p.onLime,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    // design-lint: allow truncation - an attachment's file name (content, not copy)
                    Text(content.name, style = TextStyles.subheadline.semibold, color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(byteCount(content.size), Modifier.alpha(0.7f), style = TextStyles.caption, color = ink)
                }
            }
        }

        is MessageContent.Session -> {
            // The card shows the session as it stands on the server (`SessionStore`), live; the
            // message's copy only until its row is read.
            val store = koinInject<SessionStore>()
            val snapshot = content.proposal
            val row = store.record(snapshot.id)
            val s = row?.let { SessionProposal.from(it) } ?: snapshot
            LaunchedEffect(snapshot.id) { store.need(snapshot.id) }
            SessionCard(
                session = s,
                mine = row?.let(store::isMine) ?: mine,
                profileName = convo.profile.name,
                chatID = convo.id,
                busy = store.isBusy(s.id),
                onPick = { d ->
                    app.respondToSession(s.id, accept = true, pick = d)
                    onSessionSafety(s, d)
                },
                onDecline = { app.respondToSession(s.id, accept = false) },
                onCounter = { onCounterSession(s) },
                onCancel = { app.cancelSession(s.id) },
                onSafety = { s.chosen?.let { onSessionSafety(s, it) } },
            )
        }

        is MessageContent.IcebreakerReply -> Column(
            Modifier
                .background(fill, shape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(DS.Space.sm),
        ) {
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(p.like, CircleShape))
                Column(Modifier.alpha(0.8f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        if (mine) L("Re: %s's profile", convo.profile.name) else L("Re: your profile"),
                        style = TextStyles.caption.bold,
                        color = ink,
                    )
                    // design-lint: allow truncation - quoted profile text (content, not copy)
                    Text(content.quote, style = TextStyles.footnote, color = ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            if (content.reply.isNotEmpty()) Text(content.reply, style = TextStyles.body, color = ink)
        }

        is MessageContent.PhotoReply -> Column(
            Modifier
                .background(fill, shape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(DS.Space.sm),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(DS.Space.sm), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(3.dp).height(72.dp).background(p.like, CircleShape))
                Photo(
                    content.asset,
                    Modifier
                        .size(width = 56.dp, height = 72.dp)
                        .clip(RoundedCornerShape(DS.Radius.sm)),
                    side = 56.dp,
                )
                Text(
                    if (mine) L("Liked %s's photo", convo.profile.name) else L("Liked your photo"),
                    Modifier.alpha(0.8f),
                    style = TextStyles.caption.bold,
                    color = ink,
                )
            }
            if (content.reply.isNotEmpty()) Text(content.reply, style = TextStyles.body, color = ink)
        }
    }
}

/**
 * Pixel size of a photo, to lay its bubble out in the same proportions: the size sent with it, or the
 * picture's own header. (A bundled picture falls back to the default 240 × 300 here.)
 */
private fun photoSize(message: Message, data: ByteArray?): Pair<Double, Double>? {
    message.mediaSize?.let { return it.width to it.height }
    if (data != null) return MessageImage.size(data)?.let { it.width.toDouble() to it.height.toDouble() }
    return null
}

/** A file's size the way the iPhone's `.byteCount(style: .file)` writes it: decimal units, the app's language. */
private fun byteCount(bytes: Long): String {
    val french = appLocale.language == "fr"
    val units = if (french) listOf("octets", "Ko", "Mo", "Go") else listOf("bytes", "KB", "MB", "GB")
    if (bytes < 1000) return "$bytes ${units[0]}"
    var value = bytes / 1000.0
    var unit = 1
    while (value >= 1000 && unit < units.lastIndex) {
        value /= 1000
        unit++
    }
    val text = if (value >= 100 || unit == 1) "%.0f".format(appLocale, value) else "%.1f".format(appLocale, value)
    return "$text ${units[unit]}"
}
