package so.drafft.app.feature.discover

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import so.drafft.core.data.platform.Haptics
import so.drafft.core.model.DiscoverFilters
import so.drafft.core.model.L
import so.drafft.core.model.PackPhotos
import so.drafft.core.ui.LocalAppModel
import so.drafft.core.ui.components.DrafftButton
import so.drafft.core.ui.components.Photo
import so.drafft.core.ui.components.TextLinkButton
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.LocalReduceMotion
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.display
import so.drafft.core.ui.theme.semibold

// Port of Drafft/Features/Discover/DeckEmptyView.swift.

/**
 * Discover when the stack runs out, filtered or not: one screen, the message in the middle. It shows
 * the moment the last profile card is swiped: three athlete photos are dealt into the spot it
 * leaves and fan out; then the words rise. The actions sit at the bottom: widen the
 * radius (the move that brings new people), or go to the chats.
 */
@Composable
fun DeckEmptyView(
    /**
     * True when the last card was just swiped: the entrance plays. Coming back to Discover later
     * (another tab, the app reopened) shows the screen already in place.
     */
    animate: Boolean,
    onChats: () -> Unit,
    onFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = LocalAppModel.current
    val reduceMotion = LocalReduceMotion.current
    // Read once, on appear: the entrance plays only for the swipe that emptied the stack.
    val plays = remember { animate && !reduceMotion }
    var fanIn by remember { mutableStateOf(!plays) }
    val textIn = remember { Animatable(if (plays) 0f else 1f) }

    LaunchedEffect(Unit) {
        if (!plays) return@LaunchedEffect
        // Quickly after the last card flies off: each photo carries its own delayed spring.
        launch {
            delay(80)
            fanIn = true
        }
        delay(350)
        textIn.animateTo(1f, tween(400, easing = Motion.EaseOut))
    }

    Column(
        modifier.fillMaxSize().padding(bottom = DS.Space.lg),
        verticalArrangement = Arrangement.spacedBy(DS.Space.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Column(verticalArrangement = Arrangement.spacedBy(DS.Space.xl), horizontalAlignment = Alignment.CenterHorizontally) {
            Fan(fanIn, instant = !plays)
            Message(textIn = { textIn.value })
        }
        Spacer(Modifier.weight(1f))
        Actions(onChats, onFilters)
    }
}

// MARK: Fan

/**
 * Three athletes from the pack photos, the people you're looking for ("Show me"), in the boost
 * sheet's fan: same card sizes, angles and shadow. After the last swipe they are dealt in
 * quickly, one after the other. The pile is kept by `AppModel.emptyPile`: only a new "Show me"
 * deals another one.
 */
@Composable
private fun Fan(fanIn: Boolean, instant: Boolean) {
    val app = LocalAppModel.current
    val photos = app.emptyPile
    LaunchedEffect(Unit) { if (app.emptyPile.isEmpty()) app.emptyPile = PackPhotos.pick(app.filters.audience) }
    Box(Modifier.fillMaxWidth().height(170.dp).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        photos.forEachIndexed { index, name ->
            key(name) {
                // The last photo leads in the middle; a pile of two keeps the left slot only.
                val slot = if (index == photos.size - 1) 2 else index
                FanPhoto(name, slot, index, fanIn, instant)
            }
        }
    }
}

private val fanAngles = floatArrayOf(-14f, 14f, 0f)
private val fanXs = floatArrayOf(-78f, 78f, 0f)
private val fanYs = floatArrayOf(8f, 8f, -4f)

@Composable
private fun FanPhoto(name: String, slot: Int, index: Int, fanIn: Boolean, instant: Boolean) {
    val lead = slot == 2
    val t = remember { Animatable(if (fanIn) 1f else 0f) }
    LaunchedEffect(fanIn) {
        val target = if (fanIn) 1f else 0f
        if (instant) {
            t.snapTo(target)
        } else {
            delay(index * 70L)
            t.animateTo(target, Motion.springOf(0.5, 0.72f))
        }
    }
    val w = if (lead) 108.dp else 84.dp
    val h = if (lead) 144.dp else 112.dp
    val shape = RoundedCornerShape(DS.Radius.lg)
    Box(
        Modifier
            .size(w, h)
            .graphicsLayer {
                val v = t.value
                // Rotation about the bottom edge, then the offset (as SwiftUI orders them).
                transformOrigin = TransformOrigin(0.5f, 1f)
                rotationZ = fanAngles[slot] * v
                translationX = (fanXs[slot] * v).dp.toPx()
                translationY = (fanYs[slot] * v + -40f * (1 - v)).dp.toPx()
                val s = 0.7f + 0.3f * v
                scaleX = s
                scaleY = s
                alpha = v.coerceIn(0f, 1f)
            }
            .then(if (lead) Modifier.shadow(18.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.4f), spotColor = Color.Black.copy(alpha = 0.4f)) else Modifier),
    ) {
        Photo(name, Modifier.fillMaxSize().clip(shape), side = w)
    }
}

// MARK: Message

@Composable
private fun Message(textIn: () -> Float) {
    val app = LocalAppModel.current
    val filters = app.filters
    Column(
        Modifier
            .padding(horizontal = DS.Space.lg)
            .graphicsLayer {
                val v = textIn()
                alpha = v
                translationY = (10f * (1 - v)).dp.toPx()
            },
        verticalArrangement = Arrangement.spacedBy(DS.Space.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            L("No one new for now."),
            Modifier.semantics { heading() },
            style = display(22f),
            color = DS.palette.ink,
            textAlign = TextAlign.Center,
        )
        Text(
            if (filters.anyDistance) {
                L("You've seen every profile that matches your filters. Come back later or change them.")
            } else {
                L("You've seen every profile within %s. Widen it to see more.", filters.distanceShort)
            },
            style = TextStyles.body,
            color = DS.palette.body,
            textAlign = TextAlign.Center,
        )
    }
}

// MARK: Actions

@Composable
private fun Actions(onChats: () -> Unit, onFilters: () -> Unit) {
    val app = LocalAppModel.current
    // The next step out: 25 km, 50 km, then any distance. Null once already at any distance.
    val next = listOf(25.0, 50.0, DiscoverFilters.ANY_DISTANCE).firstOrNull { it > app.filters.maxDistanceKm }
    Column(verticalArrangement = Arrangement.spacedBy(DS.Space.xs), horizontalAlignment = Alignment.CenterHorizontally) {
        if (next != null) {
            DrafftButton(
                text = if (next >= DiscoverFilters.ANY_DISTANCE) L("Widen to any distance") else L("Widen to %s", L("%d km", next.toInt())),
                onClick = {
                    Haptics.success()
                    app.filters = app.filters.copy(maxDistanceKm = next)
                },
                fullWidth = false,
            )
            Link(L("Go to chats"), onChats)
        } else {
            DrafftButton(text = L("Go to chats"), onClick = onChats, fullWidth = false)
            Link(L("Adjust filters"), onFilters)
        }
    }
}

@Composable
private fun Link(title: String, action: () -> Unit) {
    TextLinkButton(title, action, color = DS.palette.accentInk, style = TextStyles.subheadline.semibold)
}
