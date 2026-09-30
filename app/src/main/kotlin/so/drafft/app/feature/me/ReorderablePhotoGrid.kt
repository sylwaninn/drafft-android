package so.drafft.app.feature.me

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.zIndex
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import so.drafft.core.data.moderation.PhotoModeration
import so.drafft.core.data.platform.Haptics
import so.drafft.core.model.L
import so.drafft.core.ui.components.ConfirmAction
import so.drafft.core.ui.components.DrafftConfirm
import so.drafft.core.ui.components.Photo
import so.drafft.core.ui.components.PressScaleButton
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.bold
import so.drafft.core.ui.theme.heavy

// Port of Drafft/Features/Me/ReorderablePhotoGrid.swift.

private const val COLUMNS = 3
private val Spacing = DS.Space.sm

/** How long a photo is held before it lifts (the iPhone's 0.25 s long press). */
private const val HOLD_MILLIS = 250L

/**
 * 3-column photo grid. Hold a photo, then drag it: the others slide out of the way live, and the new
 * order is committed on release. [addButton] fills each free slot (up to [slots]). Sign-up can empty
 * the grid ([canRemoveLast]); a live profile always keeps one photo.
 */
@Composable
fun ReorderablePhotoGrid(
    photos: List<String>,
    onPhotosChange: (List<String>) -> Unit,
    slots: Int,
    addButton: @Composable () -> Unit,
    onRemove: (Int) -> Unit,
    canRemoveLast: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val moderation = koinInject<PhotoModeration>()
    var dragging by remember { mutableStateOf<String?>(null) }
    var order by remember { mutableStateOf(photos) }
    // Read at draw/layout time only: the finger's travel never recomposes the grid.
    val dragOffset = remember { mutableStateOf(Offset.Zero) }
    val currentPhotos by rememberUpdatedState(photos)
    val onChange by rememberUpdatedState(onPhotosChange)
    val scope = rememberCoroutineScope()

    LaunchedEffect(moderation.removeRequest) {
        val path = moderation.removeRequest ?: return@LaunchedEffect
        val i = currentPhotos.indexOf(path)
        if (i < 0) return@LaunchedEffect
        moderation.removeRequest = null
        onRemove(i)
    }

    val base = LocalViewConfiguration.current
    val hold = remember(base) {
        object : ViewConfiguration by base {
            override val longPressTimeoutMillis: Long get() = HOLD_MILLIS
        }
    }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val tileW = max(0.dp, (maxWidth - Spacing * (COLUMNS - 1)) / COLUMNS)
        val tileH = tileW * 4 / 3
        val rows = ceil(slots.toDouble() / COLUMNS).toInt()
        val gridHeight = tileH * rows + Spacing * (rows - 1).coerceAtLeast(0)
        val shown = if (dragging == null) photos else order
        val wPx = with(density) { tileW.toPx() }
        val hPx = with(density) { tileH.toPx() }
        val gapPx = with(density) { Spacing.toPx() }

        fun position(i: Int): IntOffset =
            IntOffset(((i % COLUMNS) * (wPx + gapPx)).roundToInt(), ((i / COLUMNS) * (hPx + gapPx)).roundToInt())

        /** Where the dragged tile is drawn: its start slot plus the finger's travel. */
        fun dragAnchor(name: String): IntOffset {
            val p = position(currentPhotos.indexOf(name).coerceAtLeast(0))
            val d = dragOffset.value
            return IntOffset(p.x + d.x.roundToInt(), p.y + d.y.roundToInt())
        }

        Box(Modifier.fillMaxWidth().height(gridHeight)) {
            // Add buttons fill the free slots.
            for (i in shown.size until maxOf(shown.size, slots)) {
                key("add-$i") {
                    Box(Modifier.offset { position(i) }.size(tileW, tileH)) { addButton() }
                }
            }
            CompositionLocalProvider(LocalViewConfiguration provides hold) {
                shown.forEachIndexed { i, name ->
                    key(name) {
                        val isDragged = dragging == name
                        val target = position(i)
                        val slot = remember { Animatable(target, IntOffset.VectorConverter) }
                        LaunchedEffect(target, isDragged) {
                            if (!isDragged) slot.animateTo(target, Motion.snappy())
                        }
                        val scale by animateFloatAsState(if (isDragged) 1.08f else 1f, Motion.snappy(), label = "lift")
                        Box(
                            Modifier
                                .offset { if (isDragged) dragAnchor(name) else slot.value }
                                .zIndex(if (isDragged) 1f else 0f)
                                .size(tileW, tileH)
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                }
                                .shadow(if (isDragged) 14.dp else 0.dp, RoundedCornerShape(DS.Radius.lg), clip = false)
                                .pointerInput(name, wPx, hPx) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            order = currentPhotos
                                            dragOffset.value = Offset.Zero
                                            dragging = name
                                            Haptics.thump()
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            dragOffset.value += amount
                                            // Live reorder: find the slot under the tile's centre.
                                            val start = position(currentPhotos.indexOf(name).coerceAtLeast(0))
                                            val cx = start.x + dragOffset.value.x + wPx / 2
                                            val cy = start.y + dragOffset.value.y + hPx / 2
                                            val col = (cx / (wPx + gapPx)).toInt().coerceIn(0, COLUMNS - 1)
                                            val row = (cy / (hPx + gapPx)).toInt().coerceAtLeast(0)
                                            val to = minOf(order.size - 1, row * COLUMNS + col)
                                            val from = order.indexOf(name)
                                            if (from >= 0 && from != to) {
                                                Haptics.select()
                                                order = order.toMutableList().apply {
                                                    removeAt(from)
                                                    add(to, name)
                                                }
                                            }
                                        },
                                        onDragEnd = {
                                            val here = dragAnchor(name)
                                            scope.launch {
                                                // The tile settles from where the finger left it.
                                                slot.snapTo(here)
                                                onChange(order)
                                                dragging = null
                                                dragOffset.value = Offset.Zero
                                            }
                                        },
                                        onDragCancel = {
                                            val here = dragAnchor(name)
                                            scope.launch {
                                                slot.snapTo(here)
                                                onChange(order)
                                                dragging = null
                                                dragOffset.value = Offset.Zero
                                            }
                                        },
                                    )
                                },
                        ) {
                            PhotoTile(
                                name = name,
                                index = i,
                                count = currentPhotos.size,
                                state = moderation.states[name],
                                dragging = dragging != null,
                                canRemove = (currentPhotos.size > 1 || canRemoveLast) && dragging == null,
                                onRemove = {
                                    Haptics.tap()
                                    onRemove(i)
                                },
                                onRefusedTap = { moderation.presentedRefusal = PhotoModeration.Refusal(name) },
                                onFailedTap = { moderation.shownFailure = name },
                                onMove = { to ->
                                    val list = currentPhotos.toMutableList()
                                    val item = list.removeAt(i)
                                    list.add(to, item)
                                    onChange(list)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    val failure = moderation.shownFailure
    DrafftConfirm(
        visible = failure != null && failure in photos,
        onDismissRequest = { moderation.shownFailure = null },
        icon = "exclamationmark.triangle",
        title = L("Upload failed"),
        message = failure?.let(moderation::reason),
        cancelTitle = L("OK"),
        actions = listOf(ConfirmAction(L("Retry")) { moderation.shownFailure?.let(moderation::retry) }),
    )
}

@Composable
private fun PhotoTile(
    name: String,
    index: Int,
    count: Int,
    state: PhotoModeration.State?,
    dragging: Boolean,
    canRemove: Boolean,
    onRemove: () -> Unit,
    onRefusedTap: () -> Unit,
    onFailedTap: () -> Unit,
    onMove: (Int) -> Unit,
) {
    val shape = RoundedCornerShape(DS.Radius.lg)
    val refused = state == PhotoModeration.State.Refused
    val moveEarlier = L("Move earlier")
    val moveLater = L("Move later")
    Box(
        Modifier
            .fillMaxSize()
            .clip(shape)
            .semantics {
                contentDescription = L("Photo %d of %d", index + 1, count)
                customActions = buildList {
                    if (index > 0) add(CustomAccessibilityAction(moveEarlier) { onMove(index - 1); true })
                    if (index < count - 1) add(CustomAccessibilityAction(moveLater) { onMove(index + 1); true })
                }
                if (refused) onClick { onRefusedTap(); true }
            }
            .pointerInput(refused) {
                detectTapGestures { if (refused) onRefusedTap() }
            },
    ) {
        Photo(name, Modifier.fillMaxSize())
        // Dimmed while it's not live on the profile: being checked (with a small loader), refused
        // (red chip, a tap explains why) or in review. Accepted: nothing.
        val dimmed = state != null && (state.isWorking || state == PhotoModeration.State.Refused || state == PhotoModeration.State.InReview)
        AnimatedVisibility(dimmed, Modifier.fillMaxSize(), enter = fadeIn(Motion.snappy()), exit = fadeOut(Motion.snappy())) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f)))
        }
        if (state != null && !dragging) {
            Box(Modifier.align(Alignment.BottomStart)) {
                when (state) {
                    PhotoModeration.State.Uploading, PhotoModeration.State.Checking ->
                        CircularProgressIndicator(
                            Modifier
                                .padding(DS.Space.sm)
                                .size(16.dp)
                                .semantics { contentDescription = L("Checking this photo") },
                            color = Color.White,
                            strokeWidth = 2.dp,
                        )
                    PhotoModeration.State.Approved -> Unit
                    PhotoModeration.State.Refused, PhotoModeration.State.InReview ->
                        ModerationBadge(state, Modifier.padding(DS.Space.xs))
                    is PhotoModeration.State.Failed -> {
                        // Tap for the reason, and to try again.
                        val hint = L("Shows why, and lets you try again")
                        PressScaleButton(
                            onClick = onFailedTap,
                            modifier = Modifier
                                .padding(horizontal = DS.Space.xs)
                                .defaultMinSize(minHeight = 44.dp)
                                .semantics { onClick(label = hint) { onFailedTap(); true } },
                            scale = 1f,
                        ) {
                            ModerationBadge(state)
                        }
                    }
                }
            }
        }
        AnimatedVisibility(
            canRemove,
            Modifier.align(Alignment.TopEnd),
            enter = fadeIn(Motion.snappy()) + scaleIn(Motion.snappy(), initialScale = 0.8f),
            exit = fadeOut(Motion.snappy()) + scaleOut(Motion.snappy(), targetScale = 0.8f),
        ) {
            PressScaleButton(
                onClick = onRemove,
                modifier = Modifier.size(44.dp),
                scale = 1f,
                contentDescription = L("Remove photo %d", index + 1),
            ) {
                Box(Modifier.size(26.dp).background(DS.palette.canvas, CircleShape), contentAlignment = Alignment.Center) {
                    DrafftIcon("xmark", size = 14.4.dp, tint = DS.palette.ink)
                }
            }
        }
    }
}

/**
 * Where a new photo stands with the server, when there's something to say: refused (red), in
 * review, or failed. A chip, in a block like every text.
 */
@Composable
private fun ModerationBadge(state: PhotoModeration.State, modifier: Modifier = Modifier) {
    val p = DS.palette
    val refused = state == PhotoModeration.State.Refused
    val symbol = when (state) {
        PhotoModeration.State.Refused -> "nosign"
        PhotoModeration.State.InReview -> "hourglass"
        else -> "exclamationmark.triangle"
    }
    val label = when (state) {
        PhotoModeration.State.Refused -> L("Refused")
        PhotoModeration.State.InReview -> L("In review")
        else -> L("Failed, tap to see why")
    }
    val ink = if (refused) Color.White else p.ink
    val glyph: @Composable () -> Unit = { DrafftIcon(symbol, size = 13.2.dp, tint = ink) }
    Box(
        modifier
            .height(24.dp)
            .background(if (refused) p.negative else p.canvas, CircleShape)
            .padding(horizontal = 8.dp)
            .clearAndSetSemantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        // On a narrow tile a long translation leaves the symbol alone (TalkBack still reads it).
        FitsOrFallback(
            full = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    glyph()
                    Text(label, style = TextStyles.caption2.bold, color = ink, maxLines = 1, softWrap = false)
                }
            },
            fallback = glyph,
        )
    }
}

/** `ViewThatFits(in: .horizontal)` for two options: [full] when it fits the width, [fallback] otherwise. */
@Composable
private fun FitsOrFallback(full: @Composable () -> Unit, fallback: @Composable () -> Unit) {
    Layout(contents = listOf(full, fallback)) { (a, b), constraints ->
        val wide = a.first().measure(Constraints())
        if (!constraints.hasBoundedWidth || wide.width <= constraints.maxWidth) {
            layout(wide.width, wide.height) { wide.place(0, 0) }
        } else {
            val narrow = b.first().measure(constraints.copy(minWidth = 0))
            layout(narrow.width, narrow.height) { narrow.place(0, 0) }
        }
    }
}

