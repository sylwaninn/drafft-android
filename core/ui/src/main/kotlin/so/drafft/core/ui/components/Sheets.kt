package so.drafft.core.ui.components

import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import so.drafft.core.model.L
import so.drafft.core.ui.platform.LocalPlatformUi
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.LocalIsNightSurface
import so.drafft.core.ui.theme.LocalIsSheetSurface
import so.drafft.core.ui.theme.LocalReduceMotion
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.PlainSurface
import so.drafft.core.ui.theme.displayBold

// The iPhone's presentations (`.sheet`, `.fullScreenCover`) in their Android form.

/** How tall a sheet stands, like `presentationDetents`. */
enum class SheetDetent {
    /** Nearly full height, below the status bar (the default `.sheet`). */
    LARGE,

    /** Half the screen. */
    MEDIUM,

    /** Sized to its content (confirmations, purchase confirmations, short pickers). */
    FIT,
}

/**
 * Closes the sheet the content sits in, with its slide down (like SwiftUI's `dismiss`), then calls
 * its `onDismissRequest`. Outside a sheet it does nothing.
 */
val LocalSheetDismiss = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * A modal sheet with the iPhone sheet's shape and surface. A sheet never shares the page's colour:
 * the sheet itself is the lifted white ([raised]: `sheetRaised`, a step higher still, for short
 * modal sheets that must stand out over another sheet) and its blocks sink into sage wells; the
 * content is marked as a sheet surface so `canvas` / `canvasSoft` flip by themselves. System back
 * and a drag down close it. [drawsUnderNavigationBar]: the content reaches the bottom edge and pads
 * itself (a scroll view that runs under the bar, like the iPhone's), the keyboard still lifts it.
 *
 * Present it by composing it (`if (showing) DrafftSheet(onDismissRequest = { showing = false }) { }`);
 * the content closes it with the slide through [LocalSheetDismiss].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrafftSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    detent: SheetDetent = SheetDetent.LARGE,
    showsGrabber: Boolean = true,
    raised: Boolean = false,
    dismissDisabled: Boolean = false,
    drawsUnderNavigationBar: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val lock = remember { SheetLock() }
    lock.fixed = dismissDisabled
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true) { it != SheetValue.Hidden || !lock.isLocked }
    SheetHost(state, lock, onDismissRequest, modifier, detent, showsGrabber, raised, drawsUnderNavigationBar, content)
}

/**
 * [DrafftSheet] driven by [visible], like `.sheet(isPresented:)`: turning it off slides the sheet
 * down before it leaves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrafftSheet(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    detent: SheetDetent = SheetDetent.LARGE,
    showsGrabber: Boolean = true,
    raised: Boolean = false,
    dismissDisabled: Boolean = false,
    drawsUnderNavigationBar: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val lock = remember { SheetLock() }
    lock.fixed = dismissDisabled
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true) { it != SheetValue.Hidden || !lock.isLocked }
    var shown by remember { mutableStateOf(visible) }
    LaunchedEffect(visible) {
        if (visible) {
            shown = true
        } else if (shown) {
            runCatching { state.hide() }
            shown = false
        }
    }
    if (shown) SheetHost(state, lock, onDismissRequest, modifier, detent, showsGrabber, raised, drawsUnderNavigationBar, content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetHost(
    state: SheetState,
    lock: SheetLock,
    onDismissRequest: () -> Unit,
    modifier: Modifier,
    detent: SheetDetent,
    showsGrabber: Boolean,
    raised: Boolean,
    drawsUnderNavigationBar: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = DS.palette
    val scope = rememberCoroutineScope()
    val onDismiss by rememberUpdatedState(onDismissRequest)
    val dismiss: () -> Unit = remember(state, scope) {
        { scope.launch { runCatching { state.hide() } }.invokeOnCompletion { onDismiss() } }
    }
    val surface = remember { SheetContainer() }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = !lock.isLocked),
        // A large sheet stops just below the status bar, like the iPhone's.
        modifier = if (detent == SheetDetent.LARGE) Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(top = 10.dp) else Modifier,
        sheetState = state,
        shape = RoundedCornerShape(topStart = SheetCorner, topEnd = SheetCorner),
        containerColor = surface.color ?: if (raised) p.sheetRaised else p.white,
        contentColor = p.ink,
        tonalElevation = 0.dp,
        scrimColor = Color.Black.copy(alpha = if (LocalIsNightSurface.current || p.isDark) 0.48f else 0.25f),
        dragHandle = if (showsGrabber) ({ Grabber() }) else null,
        // The bottom only (navigation bar, keyboard): the top is handled by the detent above, so a
        // short sheet never gets an empty status-bar band over its content. A sheet drawn under the
        // navigation bar keeps only the keyboard: its content pads itself, and scrolls under the bar.
        contentWindowInsets = {
            if (drawsUnderNavigationBar) {
                WindowInsets.ime.only(WindowInsetsSides.Bottom).union(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            } else {
                WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
            }
        },
    ) {
        CompositionLocalProvider(
            LocalIsSheetSurface provides true,
            LocalIsNightSurface provides false,
            LocalContentColor provides p.ink,
            LocalSheetDismiss provides dismiss,
            LocalSheetLock provides lock,
            LocalSheetContainer provides surface,
        ) {
            val height = when (detent) {
                SheetDetent.LARGE -> Modifier.fillMaxHeight()
                SheetDetent.MEDIUM -> Modifier.fillMaxHeight(0.5f)
                SheetDetent.FIT -> Modifier
            }
            Column(modifier.fillMaxWidth().then(height), content = content)
        }
    }
}

/** The colour the enclosing [DrafftSheet] paints behind its content, set by [SheetContainerColor]. */
@Stable
class SheetContainer {
    internal var color by mutableStateOf<Color?>(null)
}

private val LocalSheetContainer = compositionLocalOf<SheetContainer?> { null }

/**
 * A sheet whose content paints its own full-bleed surface (the night paywall) declares that colour
 * here, once, from inside the content. The sheet then paints the same colour behind everything the
 * content doesn't cover: the grabber band on top, the navigation bar and the keyboard below. Without
 * it those bands show the sheet's white around the content. Never rely on the content reaching the
 * edges: the sheet's container and the content's surface are always the same colour.
 */
@Composable
fun SheetContainerColor(color: Color) {
    val surface = LocalSheetContainer.current ?: return
    SideEffect { surface.color = color }
    DisposableEffect(surface) { onDispose { surface.color = null } }
}

/**
 * Whether the sheet may be closed by a drag, a tap on the scrim or system back (iOS
 * `interactiveDismissDisabled`). A closing through [LocalSheetDismiss] always works.
 */
@Stable
class SheetLock {
    internal var fixed by mutableStateOf(false)
    internal var holds by mutableIntStateOf(0)
    val isLocked: Boolean get() = fixed || holds > 0
}

val LocalSheetLock = compositionLocalOf<SheetLock?> { null }

/**
 * Keeps the enclosing [DrafftSheet] from being swiped or backed away while [disabled] (a purchase
 * running, a send in flight, unsaved changes), like SwiftUI's `.interactiveDismissDisabled(_:)`.
 */
@Composable
fun InteractiveDismissDisabled(disabled: Boolean = true) {
    val lock = LocalSheetLock.current ?: return
    if (!disabled) return
    DisposableEffect(lock) {
        lock.holds++
        onDispose { lock.holds-- }
    }
}

/** The iPhone sheets' corner. */
private val SheetCorner = 38.dp

/** The iOS grabber: a 36 × 5 pill, mute at 40 %, 5 pt below the sheet's top edge. */
@Composable
private fun Grabber() {
    Box(
        Modifier
            .padding(top = 5.dp, bottom = 3.dp)
            .size(width = 36.dp, height = 5.dp)
            .background(DS.palette.mute.copy(alpha = 0.4f), RoundedCornerShape(2.5.dp)),
    )
}

/**
 * The inline navigation bar of an iPhone sheet: the title in the middle (Inter Display ExtraBold
 * 17), close top-right as a glass circle (Close is top-right on every sheet and modal), an optional
 * [leading] control (back) on the left. 44 pt targets. A null [onClose] drops the close button: the
 * sheet's only action already just closes it ("Got it", "Done"), never two ways out.
 */
@Composable
fun SheetNavBar(
    title: String,
    onClose: (() -> Unit)? = LocalSheetDismiss.current,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .padding(horizontal = DS.Space.lg, vertical = DS.Space.xs),
    ) {
        if (leading != null) Box(Modifier.align(Alignment.CenterStart)) { leading() }
        Text(
            title,
            Modifier
                .align(Alignment.Center)
                .padding(horizontal = 52.dp)
                .semantics { heading() },
            style = displayBold(17f),
            color = DS.palette.ink,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
        if (onClose != null) {
            GlassCircleButton("close", onClose, Modifier.align(Alignment.CenterEnd), contentDescription = L("Close"))
        }
    }
}

/**
 * `.fullScreenCover`: slides up over everything (sheets included), full screen; system back calls
 * [onDismissRequest]. With Remove animations on, it fades. The cover's page is `canvasSoft` unless
 * its content paints its own.
 */
@Composable
fun FullScreenCover(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val transition = remember { MutableTransitionState(false) }
    LaunchedEffect(visible) { transition.targetState = visible }
    if (!transition.currentState && !transition.targetState && transition.isIdle) return
    val reduceMotion = LocalReduceMotion.current
    val slide = Motion.springOf(0.45, 1f, IntOffset(1, 1))
    LocalPlatformUi.current.FullScreenWindow(onDismissRequest) {
        AnimatedVisibility(
            visibleState = transition,
            enter = if (reduceMotion) fadeIn(Motion.gentle()) else slideInVertically(slide) { it },
            exit = if (reduceMotion) fadeOut(Motion.gentle()) else slideOutVertically(slide) { it },
        ) {
            PlainSurface {
                Box(modifier.fillMaxSize().background(DS.palette.canvasSoft)) { content() }
            }
        }
    }
}
