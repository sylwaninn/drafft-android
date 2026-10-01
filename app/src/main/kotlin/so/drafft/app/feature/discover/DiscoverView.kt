package so.drafft.app.feature.discover

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag as trackDrag
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import so.drafft.app.feature.chat.LikesYouView
import so.drafft.app.feature.me.PaywallView
import so.drafft.app.feature.profile.ProfileDetailMode
import so.drafft.app.feature.profile.ProfileDetailView
import so.drafft.core.data.AppModel
import so.drafft.core.data.platform.Haptics
import so.drafft.core.model.L
import so.drafft.core.model.MessageContent
import so.drafft.core.model.Profile
import so.drafft.core.ui.LocalAppModel
import so.drafft.core.ui.components.DrafftButton
import so.drafft.core.ui.components.DrafftSheet
import so.drafft.core.ui.components.EmptyStateArt
import so.drafft.core.ui.components.EmptyStateView
import so.drafft.core.ui.components.ImageStore
import so.drafft.core.ui.components.LocalTabBarInset
import so.drafft.core.ui.components.PressScaleButton
import so.drafft.core.ui.components.RollingText
import so.drafft.core.ui.components.SparkPlus
import so.drafft.core.ui.components.SuperLikeCountMark
import so.drafft.core.ui.components.Wordmark
import so.drafft.core.ui.components.glass
import so.drafft.core.ui.platform.LocalPlatformUi
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.LocalReduceMotion
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.heavy
import so.drafft.core.ui.theme.monospacedDigits
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// Port of Drafft/Features/Discover/DiscoverView.swift.

/**
 * A card on its way out. Each one leaves the deck (and the data) the moment it's swiped and
 * finishes its flight in its own layer, so the next card is in play at once: fast swipes never
 * wait for the previous card to land.
 */
private data class FlyOut(
    val profile: Profile,
    val liked: Boolean,
    val superLike: Boolean = false,
    /** Where the drag left it (px): the flight starts from there. */
    val start: Offset = Offset.Zero,
    val id: UUID = UUID.randomUUID(),
)

/** How far past the centre (in dp) the top card commits to like or pass. */
private val Threshold = 110.dp

/** Likes moved to their own tab (test). The header chip is kept for that variant; flip to bring it back. */
private const val LIKES_IN_HEADER = false

/** The deck's own swing: the stack moves up as each swiped card leaves the data (`.smooth(0.26, extraBounce: 0.04)`). */
private fun <T> deckSpring() = Motion.springOf<T>(0.26, 0.96f)

/**
 * Motion shared by the cards without recomposing them. The card that takes the lead when the top
 * one is swiped starts from where the drag had pulled it ([handoff]), not from its resting place.
 */
private class DeckMotion {
    var handoffId: String? = null
    var handoff = 0f

    /** False on the deck's first frame: cards shown then are already in place. */
    var started = false
}

@Composable
fun DiscoverView(modifier: Modifier = Modifier) {
    val app = LocalAppModel.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val threshold = with(density) { Threshold.toPx() }
    val drag = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    // -1...1: how far the top card is committed toward pass (-) or like (+). Read at draw time.
    val progress: () -> Float = { (drag.value.x / threshold).coerceIn(-1f, 1f) }
    var flying by remember { mutableStateOf(listOf<FlyOut>()) }
    var deckSize by remember { mutableStateOf(IntSize.Zero) }
    // Set by the swipe that empties the stack, cleared once the empty state has shown it: only that
    // moment plays the empty state's entrance.
    var emptiedBySwipe by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Profile?>(null) }
    var likeBurst by remember { mutableIntStateOf(0) }
    var passBurst by remember { mutableIntStateOf(0) }
    var superBurst by remember { mutableIntStateOf(0) }
    var showPaywall by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    var extras by remember { mutableStateOf<ExtrasSheet.Tab?>(null) }
    var showLikes by remember { mutableStateOf(false) }
    var showLikesPaywall by remember { mutableStateOf(false) }
    // Waiting for confirmation before spending a super like.
    var superLikeTarget by remember { mutableStateOf<Profile?>(null) }
    val motion = remember { DeckMotion() }

    fun commit(p: Profile, liked: Boolean, superLike: Boolean = false, opener: MessageContent? = null) {
        // Out of likes or super likes: the card springs back and the extras sheet explains why.
        if (superLike && app.superLikes == 0 || liked && !superLike && !app.canLike) {
            Haptics.warning()
            scope.launch { drag.animateTo(Offset.Zero, Motion.bouncy()) }
            extras = if (superLike) ExtrasSheet.Tab.SUPER_LIKE else ExtrasSheet.Tab.LIKES
            return
        }
        if (liked) Haptics.thump() else Haptics.tap()
        if (superLike) superBurst++ else if (liked) likeBurst++ else passBurst++
        // The card leaves the deck and the data now; its flight carries on in the flying layer, so
        // the next card is in play at once and a quick run of swipes never waits.
        val flyOut = FlyOut(p, liked, superLike, start = drag.value)
        val index = app.deck.indexOfFirst { it.id == p.id }
        motion.handoffId = app.deck.getOrNull(index + 1)?.id
        motion.handoff = abs(progress()) * 0.5f
        // At once, without animation: the next card must not inherit this one's drag for a frame.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { drag.snapTo(Offset.Zero) }
        flying = flying + flyOut
        app.swipe(p, liked = liked, superLike = superLike, opener = opener)
        if (app.deck.isEmpty()) emptiedBySwipe = true
        scope.launch {
            delay(320)
            flying = flying.filterNot { it.id == flyOut.id }
        }
    }

    // Confirm before spending a super like; with none left, the extras sheet offers packs.
    fun askSuperLike(p: Profile) {
        if (app.superLikes <= 0) {
            Haptics.warning()
            extras = ExtrasSheet.Tab.SUPER_LIKE
            return
        }
        Haptics.tap()
        superLikeTarget = p
    }

    // Header, deck and buttons share one column so a dragged card can pass over the header
    // (it's drawn before the deck) but never over the like/pass buttons (drawn after it).
    Column(
        modifier
            .fillMaxSize()
            .background(DS.palette.canvasSoft)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(bottom = LocalTabBarInset.current)
            .padding(horizontal = DS.Space.md),
    ) {
        TopBar(
            onFilters = {
                Haptics.tap()
                showFilters = true
            },
            onWallet = {
                Haptics.tap()
                extras = ExtrasSheet.Tab.BOOST
            },
            onLikes = {
                Haptics.tap()
                if (app.isPremium) showLikes = true else showLikesPaywall = true
            },
            modifier = Modifier.zIndex(0f),
        )
        Box(Modifier.fillMaxWidth().weight(1f).zIndex(1f), contentAlignment = Alignment.TopCenter) {
            if (app.deck.isEmpty()) {
                // Right away, even while the last card is still flying over it.
                when (val state = app.deckState) {
                    AppModel.DeckState.Loading -> LoadingState()
                    is AppModel.DeckState.Failed -> FailedState(state.text, Modifier.padding(bottom = DS.Space.xl))
                    AppModel.DeckState.Idle, AppModel.DeckState.Loaded -> {
                        // The stack ran out (filtered or not): the same screen either way.
                        DeckEmptyView(
                            animate = emptiedBySwipe,
                            onChats = { app.tab = AppModel.Tab.CHATS },
                            onFilters = { showFilters = true },
                            modifier = Modifier.padding(horizontal = DS.Space.md).padding(bottom = DS.Space.xl),
                        )
                        LaunchedEffect(Unit) { emptiedBySwipe = false }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Deck(
                        drag = drag,
                        progress = progress,
                        threshold = threshold,
                        motion = motion,
                        onSize = { deckSize = it },
                        onCommit = { p, liked -> commit(p, liked) },
                        onSuperLike = ::askSuperLike,
                        onOpen = { detail = it },
                        modifier = Modifier.fillMaxWidth().weight(1f).zIndex(1f),
                    )
                    // Same space above and below: centred between the cards and the tab bar.
                    Actions(
                        progress = progress,
                        likeBurst = likeBurst,
                        passBurst = passBurst,
                        superBurst = superBurst,
                        onUndo = {
                            if (app.isPremium) {
                                app.undo()
                            } else {
                                Haptics.tap()
                                showPaywall = true
                            }
                        },
                        onPass = { app.topCard?.let { commit(it, liked = false) } },
                        onLike = { app.topCard?.let { commit(it, liked = true) } },
                        onSuperLike = { app.topCard?.let(::askSuperLike) },
                        modifier = Modifier.padding(vertical = DS.Space.lg).zIndex(2f),
                    )
                }
            }
            FlyingLayer(flying, app.me, deckSize)
        }
    }
    SideEffect { motion.started = true }

    // Presentations.
    DrafftSheet(visible = showFilters, onDismissRequest = { showFilters = false }) {
        FiltersSheet(filters = app.filters)
    }
    DrafftSheet(visible = showLikes, onDismissRequest = { showLikes = false }) {
        LikesYouView()
    }
    DrafftSheet(visible = showLikesPaywall, onDismissRequest = { showLikesPaywall = false }) {
        PaywallView(
            onUnlocked = { showLikes = true },
            headline = L("See who likes you."),
            pitch = L("drafft tempo shows everyone who already liked you, so you can match in one tap."),
            unlockedTitle = L("See who likes you"),
        )
    }
    val shownExtras = rememberLast(extras)
    DrafftSheet(visible = extras != null, onDismissRequest = { extras = null }) {
        shownExtras?.let { ExtrasSheet(tab = it) }
    }
    DrafftSheet(visible = showPaywall, onDismissRequest = { showPaywall = false }) {
        PaywallView(onUnlocked = { app.undo() }, unlockedTitle = L("Undo my last swipe"))
    }
    // A window over everything, the tab bar included, without its own transition: the composer
    // fades itself, and is removed without a second fade.
    superLikeTarget?.let { p ->
        key(p.id) {
            LocalPlatformUi.current.FullScreenWindow(onDismissRequest = { superLikeTarget = null }) {
                SuperLikeComposer(
                    profile = p,
                    left = app.superLikes,
                    onSend = { note ->
                        superLikeTarget = null
                        commit(p, liked = true, superLike = true, opener = if (note.isEmpty()) null else MessageContent.Text(note))
                    },
                    onCancel = { superLikeTarget = null },
                )
            }
        }
    }
    val shownDetail = rememberLast(detail)
    DrafftSheet(visible = detail != null, onDismissRequest = { detail = null }, showsGrabber = false, drawsUnderNavigationBar = true) {
        shownDetail?.let { p ->
            ProfileDetailView(
                profile = p,
                mode = ProfileDetailMode.DISCOVER,
                onDecision = { liked, opener ->
                    detail = null
                    commit(p, liked = liked, opener = opener)
                },
                onSuperLike = { opener ->
                    detail = null
                    commit(p, liked = true, superLike = true, opener = opener)
                },
            )
        }
    }
}

/** The last non-null value, so a sheet keeps its content while it slides away. */
@Composable
private fun <T : Any> rememberLast(value: T?): T? {
    val last = remember { arrayOfNulls<Any>(1) }
    if (value != null) last[0] = value
    @Suppress("UNCHECKED_CAST")
    return last[0] as T?
}

// MARK: Deck

/**
 * How far back a card sits in the stack (0 = in play). While the top card flies out, every card
 * behind moves up one step, and the one right behind also follows the drag, so the stack
 * glides forward instead of snapping once the top card is gone.
 */
@Composable
private fun Deck(
    drag: Animatable<Offset, *>,
    progress: () -> Float,
    threshold: Float,
    motion: DeckMotion,
    onSize: (IntSize) -> Unit,
    onCommit: (Profile, Boolean) -> Unit,
    onSuperLike: (Profile) -> Unit,
    onOpen: (Profile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = LocalAppModel.current
    val context = LocalPlatformContext.current
    BoxWithConstraints(modifier.onSizeChanged(onSize)) {
        val width = maxWidth
        val height = maxHeight
        // One extra card is kept hidden at the back so it can fade in when the stack moves up.
        val shown = app.deck.take(4)
        for (i in shown.indices.reversed()) {
            val p = shown[i]
            key(p.id) {
                DeckCard(
                    profile = p,
                    index = i,
                    me = app.me,
                    width = width,
                    height = height,
                    drag = drag,
                    progress = progress,
                    threshold = threshold,
                    motion = motion,
                    onCommit = onCommit,
                    onSuperLike = onSuperLike,
                    onOpen = onOpen,
                    modifier = Modifier.zIndex(if (i == 0) 10f else (4 - i).toFloat()),
                )
            }
        }
        // Every photo of the card in play and the next three, fetched ahead at the card's size:
        // swiping or opening a profile never waits on the network.
        val cardWidth = with(LocalDensity.current) { width.roundToPx() }
        val cardHeight = with(LocalDensity.current) { (height - 28.dp).roundToPx() }
        LaunchedEffect(shown.map { it.id }, cardWidth, cardHeight) {
            val loader = SingletonImageLoader.get(context)
            shown.flatMap { it.allPhotos }.filter { it.startsWith("http") || it.startsWith("/") }.forEach {
                loader.enqueue(ImageStore.remoteRequest(context, it, cardWidth, cardHeight))
            }
        }
    }
}

@Composable
private fun DeckCard(
    profile: Profile,
    index: Int,
    me: Profile,
    width: Dp,
    height: Dp,
    drag: Animatable<Offset, *>,
    progress: () -> Float,
    threshold: Float,
    motion: DeckMotion,
    onCommit: (Profile, Boolean) -> Unit,
    onSuperLike: (Profile) -> Unit,
    onOpen: (Profile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isTop = index == 0
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val depth = remember { Animatable(index.toFloat()) }
    // Undo brings a card back on top with a soft scale-in; cards there from the first frame don't.
    val appear = remember { Animatable(if (motion.started) 0f else 1f) }
    LaunchedEffect(index) { depth.animateTo(index.toFloat(), deckSpring()) }
    LaunchedEffect(Unit) { appear.animateTo(1f, deckSpring()) }
    val currentIndex by rememberUpdatedState(index)
    val commitNow by rememberUpdatedState(onCommit)
    val id = profile.id
    fun visualDepth(): Float {
        val d = depth.value
        return when (currentIndex) {
            0 -> if (motion.handoffId == id) d * (1f - motion.handoff) else d
            1 -> max(0f, d - abs(progress()) * 0.5f)
            else -> d
        }
    }
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val cardHeight = height - 28.dp
    val palette = DS.palette
    val veil = palette.deckVeil
    val edge = palette.blockEdge
    val radius = with(density) { DS.Radius.xl.toPx() }

    // The drag and its rotation, about the bottom of the deck's frame (as the iPhone turns it).
    Box(
        modifier
            .size(width, height)
            .graphicsLayer {
                if (!isTop) return@graphicsLayer
                val d = drag.value
                val deg = d.x / density.density / 18f
                val rad = deg * PI.toFloat() / 180f
                transformOrigin = TransformOrigin(0.5f, 1f)
                rotationZ = deg
                // SwiftUI turns the offset view about its unmoved frame: the offset turns too.
                translationX = d.x * cos(rad) - d.y * sin(rad)
                translationY = d.x * sin(rad) + d.y * cos(rad)
            },
    ) {
        Box(
            Modifier
                .size(width, cardHeight)
                .graphicsLayer {
                    val d = visualDepth()
                    val a = appear.value
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    val s = (1f - d * 0.07f) * (0.92f + 0.08f * a)
                    scaleX = s
                    scaleY = s
                    translationY = d * 14.dp.toPx()
                    alpha = if (d > 2.5f) 0f else a.coerceIn(0f, 1f)
                    shadowElevation = 20.dp.toPx()
                    shape = RoundedCornerShape(DS.Radius.xl)
                    clip = false
                    val strength = if (isTop) 0.22f else max(0f, 1f - d) * 0.22f
                    ambientShadowColor = Color.Black.copy(alpha = strength)
                    spotShadowColor = Color.Black.copy(alpha = strength)
                }
                .onPlaced { coordinates[0] = it }
                .then(
                    if (isTop) {
                        Modifier.pointerInput(id) {
                            swipeGesture(
                                offset = drag,
                                threshold = threshold,
                                coordinates = { coordinates[0] },
                                launch = { block -> scope.launch(start = CoroutineStart.UNDISPATCHED) { block() } },
                                onCommit = { liked -> commitNow(profile, liked) },
                            )
                        }
                    } else {
                        Modifier
                    },
                )
                .then(
                    if (isTop) {
                        Modifier.semantics {
                            customActions = listOf(
                                CustomAccessibilityAction(L("Like")) { onCommit(profile, true); true },
                                CustomAccessibilityAction(L("Super like")) { onSuperLike(profile); true },
                                CustomAccessibilityAction(L("Pass")) { onCommit(profile, false); true },
                                CustomAccessibilityAction(L("Open profile")) { onOpen(profile); true },
                            )
                        }
                    } else {
                        Modifier.clearAndSetSemantics { }
                    },
                )
                // Cards behind are veiled so the top card reads as the one in play; a faint edge in
                // dark mode so stacked cards don't merge with the page.
                .drawWithContent {
                    drawContent()
                    val corner = CornerRadius(radius)
                    if (!isTop) {
                        val d = visualDepth()
                        val amount = min(d, 1f) * 0.55f + max(0f, d - 1f) * 0.2f
                        drawRoundRect(veil, cornerRadius = corner, alpha = amount.coerceIn(0f, 1f))
                    }
                    val w = 1.dp.toPx()
                    drawRoundRect(
                        edge,
                        topLeft = Offset(w / 2, w / 2),
                        size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
                        cornerRadius = CornerRadius(radius - w / 2),
                        style = Stroke(w),
                    )
                },
        ) {
            CompositionLocalProvider(LocalCardInteractive provides isTop) {
                SwipeCard(
                    profile = profile,
                    me = me,
                    progress = if (isTop) progress else ZeroProgress,
                    isTop = isTop,
                    onOpen = { onOpen(profile) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private val ZeroProgress: () -> Float = { 0f }

/**
 * The top card follows the finger from the touch (like SwiftUI's `translation`), a haptic tick
 * when it crosses the threshold either way, and on release a decision from where it is and where
 * the fling would carry it. Positions are read in the window, so the card moving under the finger
 * never feeds back into the drag.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.swipeGesture(
    offset: Animatable<Offset, *>,
    threshold: Float,
    coordinates: () -> LayoutCoordinates?,
    launch: (suspend () -> Unit) -> Unit,
    onCommit: (Boolean) -> Unit,
) {
    val tracker = VelocityTracker()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val coords = coordinates() ?: return@awaitEachGesture
        val startRoot = coords.localToRoot(down.position)
        val startDrag = offset.value
        tracker.resetTracking()
        tracker.addPosition(down.uptimeMillis, startRoot)
        val slop = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() } ?: return@awaitEachGesture

        fun follow(change: androidx.compose.ui.input.pointer.PointerInputChange) {
            val c = coordinates() ?: return
            val root = c.localToRoot(change.position)
            tracker.addPosition(change.uptimeMillis, root)
            val target = startDrag + (root - startRoot)
            val crossedBefore = abs(offset.value.x) >= threshold
            if (crossedBefore != (abs(target.x) >= threshold)) Haptics.select()
            launch { offset.snapTo(target) }
        }

        follow(slop)
        val completed = trackDrag(slop.id) { change ->
            change.consume()
            follow(change)
        }
        val translation = offset.value.x
        if (!completed) {
            launch { offset.animateTo(Offset.Zero, Motion.bouncy()) }
            return@awaitEachGesture
        }
        // Where the fling would come to rest: UIKit's projection at the normal deceleration rate
        // (0.998 per ms), the same idea as SwiftUI's `predictedEndTranslation`.
        val velocity = tracker.calculateVelocity().x
        val predicted = translation + velocity * 0.499f
        if (abs(translation) > threshold || abs(predicted) > threshold * 2.4f) {
            onCommit((if (abs(predicted) > abs(translation)) predicted else translation) > 0)
        } else {
            launch { offset.animateTo(Offset.Zero, Motion.bouncy()) }
        }
    }
}

/**
 * Swiped cards finishing their flight above everything, the empty state included. Never
 * touchable: the card under them is already in play.
 */
@Composable
private fun FlyingLayer(flying: List<FlyOut>, me: Profile, deckSize: IntSize) {
    if (flying.isEmpty() || deckSize == IntSize.Zero) return
    val density = LocalDensity.current
    val width = with(density) { deckSize.width.toDp() }
    val height = with(density) { deckSize.height.toDp() }
    Box(Modifier.fillMaxWidth().clearAndSetSemantics { }, contentAlignment = Alignment.TopCenter) {
        flying.forEach { f ->
            key(f.id) { FlyingCard(f, me, width, height) }
        }
    }
}

/**
 * A swiped card finishing its flight: starts where the drag left it and leaves the screen with
 * its LIKE/PASS stamp fully shown, in one smooth move.
 */
@Composable
private fun FlyingCard(flyOut: FlyOut, me: Profile, width: Dp, height: Dp) {
    val gone = remember { Animatable(0f) }
    LaunchedEffect(Unit) { gone.animateTo(1f, deckSpring()) }
    val density = LocalDensity.current
    val stamp = if (flyOut.liked) 1f else -1f
    Box(
        Modifier
            .size(width, height)
            .graphicsLayer {
                val t = gone.value
                val w = width.toPx()
                val start = flyOut.start
                val end = if (flyOut.superLike) {
                    Offset(0f, -w * 2.2f)
                } else {
                    Offset((if (flyOut.liked) 1f else -1f) * w * 1.6f, start.y + 40.dp.toPx())
                }
                val startDeg = start.x / density.density / 18f
                val endDeg = if (flyOut.superLike) 0f else if (flyOut.liked) 18f else -18f
                val d = start + (end - start) * t
                val deg = startDeg + (endDeg - startDeg) * t
                val rad = deg * PI.toFloat() / 180f
                // Same pivot as the deck: the flight picks up exactly where the drag left the card.
                transformOrigin = TransformOrigin(0.5f, 1f)
                rotationZ = deg
                translationX = d.x * cos(rad) - d.y * sin(rad)
                translationY = d.x * sin(rad) + d.y * cos(rad)
            },
    ) {
        Box(
            Modifier
                .size(width, height - 28.dp)
                .graphicsLayer {
                    shadowElevation = 20.dp.toPx()
                    shape = RoundedCornerShape(DS.Radius.xl)
                    clip = false
                    ambientShadowColor = Color.Black.copy(alpha = 0.22f)
                    spotShadowColor = Color.Black.copy(alpha = 0.22f)
                },
        ) {
            CompositionLocalProvider(LocalCardInteractive provides false) {
                SwipeCard(flyOut.profile, me, progress = { stamp }, isTop = true, onOpen = {}, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

// MARK: Top bar

@Composable
private fun TopBar(onFilters: () -> Unit, onWallet: () -> Unit, onLikes: () -> Unit, modifier: Modifier = Modifier) {
    val app = LocalAppModel.current
    val p = DS.palette
    val count = app.filters.activeCount
    // Same paddings as TabHeader; Discover doesn't scroll, so it's a plain row.
    // Boosts sit on the screen's centre line unless the wordmark needs the room.
    CenterUnlessCrowded(
        modifier
            .fillMaxWidth()
            .padding(horizontal = DS.Space.lg - DS.Space.md) // the column already pads md
            .padding(top = DS.Space.xs, bottom = DS.Space.sm)
            .height(44.dp),
    ) {
        // The wordmark never truncates; the others give way.
        Row(
            Modifier.padding(start = DS.Space.xs),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Wordmark(size = 30f, color = p.ink)
            // drafft tempo members get the spark next to the wordmark, as on the paywall.
            AnimatedVisibility(
                app.isPremium,
                enter = scaleIn(Motion.bouncy()) + fadeIn(Motion.bouncy()),
                exit = scaleOut(Motion.bouncy()) + fadeOut(Motion.bouncy()),
            ) {
                Box(
                    Modifier
                        .size(30.dp, 21.dp)
                        .background(p.lime, SparkPlus())
                        .semantics { contentDescription = L("Plus") },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (LIKES_IN_HEADER && app.likedMe.isNotEmpty()) {
                LikesChip(likers = app.likedMe, unlocked = app.isPremium, action = onLikes)
            }
            // Boosts left, or the running boost as a live clock. Opens the boost sheet.
            BoostChip(boosts = app.boosts, endsAt = app.boostEndsAt, action = onWallet)
        }
        PressScaleButton(
            onClick = onFilters,
            modifier = Modifier.defaultMinSize(minHeight = 44.dp),
            scale = 0.95f,
            contentDescription = L("Filters, %s, %d active", app.filters.distanceLabel, count),
        ) {
            // The count lives inside the pill (never a badge hanging off its corner).
            Row(
                Modifier
                    .animateContentSize(Motion.snappy())
                    .defaultMinSize(minWidth = 40.dp, minHeight = 40.dp)
                    .background(p.canvas, CircleShape)
                    .padding(start = if (count > 0) 11.dp else 0.dp, end = if (count > 0) 9.dp else 0.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DrafftIcon("tuning-2", size = (17f * 1.2f).dp, tint = p.ink)
                val last = remember { IntArray(1) }
                if (count > 0) last[0] = count
                AnimatedVisibility(
                    count > 0,
                    enter = scaleIn(Motion.snappy(), initialScale = 0.6f) + fadeIn(Motion.snappy()),
                    exit = scaleOut(Motion.snappy(), targetScale = 0.6f) + fadeOut(Motion.snappy()),
                ) {
                    Box(Modifier.defaultMinSize(22.dp, 22.dp).background(p.lime, CircleShape), contentAlignment = Alignment.Center) {
                        RollingText(
                            "${last[0]}",
                            style = TextStyles.caption.heavy.monospacedDigits,
                            color = p.onLime,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Three items in a row: first on the left, last on the right, the middle one on the exact
 * centre line. When the left item is too wide for that, the middle one slides right just
 * enough to keep a gap (and never runs into the right item).
 */
@Composable
fun CenterUnlessCrowded(modifier: Modifier = Modifier, gap: Dp = 8.dp, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val s = measurables.map { it.measure(loose) }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else s.sumOf { it.width }
        val height = max(constraints.minHeight, s.maxOfOrNull { it.height } ?: 0)
        layout(width, height) {
            if (s.size != 3) return@layout
            val g = gap.roundToPx()
            s[0].place(0, (height - s[0].height) / 2)
            s[2].place(width - s[2].width, (height - s[2].height) / 2)
            val minX = s[0].width + g
            val maxX = width - s[2].width - g - s[1].width
            val x = min(max(width / 2 - s[1].width / 2, minX), max(minX, maxX))
            s[1].place(x, (height - s[1].height) / 2)
        }
    }
}

// MARK: Actions

@Composable
private fun Actions(
    progress: () -> Float,
    likeBurst: Int,
    passBurst: Int,
    superBurst: Int,
    onUndo: () -> Unit,
    onPass: () -> Unit,
    onLike: () -> Unit,
    onSuperLike: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = LocalAppModel.current
    val p = DS.palette
    // The buttons follow the drag on their own spring (`.interactiveSpring(response: 0.3, dampingFraction: 0.8)`).
    val follow = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        snapshotFlow { progress() }.collectLatest { follow.animateTo(it, Motion.springOf(0.3, 0.8f)) }
    }
    val passBounce = rememberBounce(passBurst)
    val likeBounce = rememberBounce(likeBurst)
    val superBounce = rememberBounce(superBurst)
    val canUndo = app.canUndo

    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DS.Space.lg, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Ghost undo: a drafft tempo feature, free users hit the paywall.
        PressScaleButton(
            onClick = onUndo,
            modifier = Modifier.size(52.dp).graphicsLayer { alpha = if (canUndo) 1f else 0.35f },
            scale = 0.88f,
            enabled = canUndo,
            contentDescription = L("Undo last swipe"),
        ) {
            DrafftIcon("undo-left", size = (20f * 1.2f).dp, tint = p.ink)
        }

        // While dragging, the button on that side grows; the other one shrinks back a little.
        PressScaleButton(onClick = onPass, scale = 0.88f, contentDescription = L("Pass")) {
            Box(
                Modifier
                    .size(72.dp)
                    .graphicsLayer {
                        val f = follow.value
                        val s = 1f + max(0f, -f) * 0.18f - max(0f, f) * 0.08f
                        scaleX = s
                        scaleY = s
                    }
                    .glass(CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                DrafftIcon(
                    "close",
                    Modifier.graphicsLayer { scaleX = passBounce.value; scaleY = passBounce.value },
                    size = (28f * 1.2f).dp,
                    tint = p.ink,
                )
            }
        }

        PressScaleButton(onClick = onLike, scale = 0.88f, contentDescription = L("Like")) {
            Box(
                Modifier
                    .size(72.dp)
                    .graphicsLayer {
                        val f = follow.value
                        val s = 1f + max(0f, f) * 0.18f - max(0f, -f) * 0.08f
                        scaleX = s
                        scaleY = s
                    }
                    .background(p.like, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                DrafftIcon(
                    "heart",
                    Modifier.graphicsLayer { scaleX = likeBounce.value; scaleY = likeBounce.value },
                    size = (30f * 1.2f).dp,
                    tint = p.onLike,
                )
                HeartBurst(trigger = likeBurst)
            }
        }

        // Same width as undo, so cross and heart stay centred.
        PressScaleButton(onClick = onSuperLike, scale = 0.88f, contentDescription = L("Super like, %d left", app.superLikes)) {
            SuperLikeCountMark(
                count = app.superLikes,
                size = 52.dp,
                modifier = Modifier.graphicsLayer { scaleX = superBounce.value; scaleY = superBounce.value },
            )
        }
    }
}

/** A symbol's bounce (`.symbolEffect(.bounce, value:)`): up and back each time [trigger] changes. */
@Composable
private fun rememberBounce(trigger: Int): Animatable<Float, AnimationVector1D> {
    val scale = remember { Animatable(1f) }
    val reduceMotion = LocalReduceMotion.current
    LaunchedEffect(trigger) {
        if (trigger == 0 || reduceMotion) return@LaunchedEffect
        scale.animateTo(
            1f,
            keyframes {
                durationMillis = 420
                1f at 0
                1.22f at 130 using Motion.EaseOut
                0.94f at 280
                1f at 420
            },
        )
    }
    return scale
}

/** Small hearts that pop out of the like button. */
@Composable
fun HeartBurst(trigger: Int, modifier: Modifier = Modifier) {
    val reduceMotion = LocalReduceMotion.current
    val fired = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        if (trigger == 0 || reduceMotion) return@LaunchedEffect
        fired.snapTo(0f)
        fired.animateTo(1f, tween(400, easing = Motion.EaseOut))
    }
    if (trigger == 0 || reduceMotion) return
    val p = DS.palette
    Box(modifier.clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        for (i in 0 until 7) {
            val angle = (i / 7.0 * 360 - 90) * PI / 180
            val size = (10 + (i % 3) * 3).toFloat()
            DrafftIcon(
                "heart",
                Modifier.graphicsLayer {
                    val f = fired.value
                    translationX = (cos(angle) * 64 * f).toFloat().dp.toPx()
                    translationY = (sin(angle) * 64 * f).toFloat().dp.toPx()
                    val s = 0.2f + 0.8f * f
                    scaleX = s
                    scaleY = s
                    alpha = 1f - f
                },
                size = (size * 1.2f).dp,
                tint = if (i % 2 == 0) p.like else p.likeActive,
            )
        }
    }
}

// MARK: States

/** The first batch on its way (nothing kept from last time). */
@Composable
private fun LoadingState() {
    Box(
        Modifier.fillMaxSize().semantics { contentDescription = L("Loading profiles") },
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(Modifier.size(36.dp), color = DS.palette.ink)
    }
}

/** The deck couldn't be read (offline, or the server refused): why, and a retry. */
@Composable
private fun FailedState(message: String, modifier: Modifier = Modifier) {
    val app = LocalAppModel.current
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyStateView(art = EmptyStateArt.discover, title = L("No profiles right now."), message = message) {
            DrafftButton(
                text = L("Try again"),
                onClick = {
                    Haptics.tap()
                    app.loadDeck(AppModel.DeckLoad.REFRESH)
                },
                fullWidth = false,
            )
        }
    }
}
