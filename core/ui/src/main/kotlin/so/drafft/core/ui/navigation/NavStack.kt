package so.drafft.core.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import so.drafft.core.ui.theme.Motion

/**
 * A push stack: the screens are plain values (routes) the stack owns; the root is always there. Each tab has its own;
 * a sheet that pushes gets its own too (a flow started in a sheet stays in it, and closing returns to where it
 * started).
 */
@Stable
class NavStack(root: Any) {
    private val entries = mutableStateListOf(Entry(root, 0))
    private var nextKey = 1

    /** Direction of the last change, for the transition (push slides in from the trailing edge). */
    internal var lastWasPop by mutableStateOf(false)
        private set

    val routes: List<Any> get() = entries.map { it.route }
    val top: Any get() = entries.last().route
    val depth: Int get() = entries.size
    val canPop: Boolean get() = entries.size > 1

    internal val topEntry: Entry get() = entries.last()

    /** Keys of screens popped since the host last cleared their saved state. */
    internal val dropped = mutableListOf<Int>()

    private fun drop(from: Int) {
        dropped += entries.subList(from, entries.size).map { it.key }
        entries.removeRange(from, entries.size)
    }

    fun push(route: Any) {
        lastWasPop = false
        entries += Entry(route, nextKey++)
    }

    fun pop() {
        if (!canPop) return
        lastWasPop = true
        drop(entries.lastIndex)
    }

    /** Back to the root (a tab tapped again, a flow finished). */
    fun popToRoot() {
        if (!canPop) return
        lastWasPop = true
        drop(1)
    }

    /** Pops back to the last entry matching [predicate], if any. */
    fun popTo(predicate: (Any) -> Boolean) {
        val i = entries.indexOfLast { predicate(it.route) }
        if (i < 0 || i == entries.lastIndex) return
        lastWasPop = true
        drop(i + 1)
    }

    /** Replaces the whole path above the root (opening a chat from a notification). */
    fun reset(vararg path: Any) {
        lastWasPop = false
        drop(1)
        path.forEach { entries += Entry(it, nextKey++) }
    }

    internal data class Entry(val route: Any, val key: Int)
}

/** The stack the current screen lives in: `LocalNavStack.current.push(...)`. */
val LocalNavStack = staticCompositionLocalOf<NavStack> { error("No NavStack here") }

/**
 * False where system back must not reach the stacks below: a tab that isn't the current one (every
 * tab stays composed, so a chat left open in Chats would otherwise swallow back on Discover).
 */
val LocalNavBackEnabled = compositionLocalOf { true }

@Composable
fun rememberNavStack(root: Any): NavStack = remember { NavStack(root) }

/**
 * Draws the top of [stack] with a push and pop (the new screen slides in from the trailing
 * edge over the old one, which drifts a third of the way), system back pops. Screens below keep their
 * state (scroll position, fields) while covered.
 */
@Composable
fun NavStackHost(
    stack: NavStack,
    modifier: Modifier = Modifier,
    backEnabled: Boolean = true,
    content: @Composable (route: Any) -> Unit,
) {
    val saveable = rememberSaveableStateHolder()
    BackHandler(enabled = backEnabled && LocalNavBackEnabled.current && stack.canPop) { stack.pop() }
    // Popped screens forget their saved state once their exit has played.
    LaunchedEffect(stack.topEntry.key) {
        delay(PUSH_MILLIS.toLong() + 50)
        stack.dropped.forEach(saveable::removeState)
        stack.dropped.clear()
    }
    CompositionLocalProvider(LocalNavStack provides stack) {
        AnimatedContent(
            targetState = stack.topEntry,
            modifier = modifier.fillMaxSize(),
            transitionSpec = { pushTransition(pop = stack.lastWasPop) },
            contentKey = { it.key },
            label = "NavStack",
        ) { entry ->
            saveable.SaveableStateProvider(entry.key) {
                Box(Modifier.fillMaxSize()) { content(entry.route) }
            }
        }
    }
}

private const val PUSH_MILLIS = 350

private fun pushTransition(pop: Boolean): ContentTransform {
    val spec = tween<androidx.compose.ui.unit.IntOffset>(PUSH_MILLIS, easing = Motion.EaseInOut)
    val fade = tween<Float>(PUSH_MILLIS, easing = Motion.EaseInOut)
    return if (!pop) {
        (slideInHorizontally(spec) { it } togetherWith slideOutHorizontally(spec) { -it / 3 } + fadeOut(fade, 0.85f))
            .apply { targetContentZIndex = 1f }
    } else {
        (slideInHorizontally(spec) { -it / 3 } + fadeIn(fade, 0.85f) togetherWith slideOutHorizontally(spec) { it })
            .apply { targetContentZIndex = -1f }
    }
}
