package so.drafft.app.feature.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import so.drafft.app.feature.discover.ProfileIdentity
import so.drafft.core.data.BlurredLike
import so.drafft.core.data.platform.Haptics
import so.drafft.core.model.L
import so.drafft.core.model.Profile
import so.drafft.core.model.ThumbHash
import so.drafft.core.ui.components.LocalTabIsCurrent
import so.drafft.core.ui.components.LocalTabsOnScreen
import so.drafft.core.ui.components.NightBlock
import so.drafft.core.ui.components.Photo
import so.drafft.core.ui.components.PressScaleButton
import so.drafft.core.ui.components.StickerVisits
import so.drafft.core.ui.components.SuperLikeMark
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.LocalReduceMotion
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.display

// Port of Drafft/Features/Chat/LikesMosaic.swift.

/** Tile heights shared by the mosaic and its lead block. */
object LikesTileHeight {
    val tall = 252.dp
    val short = 200.dp
}

/** What the lead block counts for when the columns are balanced (at least a tall tile). */
private val LeadWeight = LikesTileHeight.tall + 20.dp

/** Tiles past this one arrive together with it: a long list doesn't make you wait. */
private const val STAGGER_CAP = 9

private class Slot<T>(val item: T, val height: Dp, val order: Int)

/**
 * The Likes mosaic: two staggered columns of portrait tiles, tall and short in turn like a contact
 * sheet, with a lead block (the count, or what to do) as the first tile of the left column. Used by
 * the Likes tab and the drafft tempo likes sheet, blurred or sharp.
 *
 * Arriving on it after a while (`StickerVisits`, the same 30 s rule as the empty-tab sticker) files
 * the tiles in from the left, one after the other, like riders tucking into a draft. A quick round
 * of tabs or the walk under the splash doesn't replay it. Reduce Motion: already in place.
 */
@Composable
fun <T> LikesMosaic(
    items: List<T>,
    itemKey: (T) -> Any,
    visitKey: String,
    modifier: Modifier = Modifier,
    lead: @Composable () -> Unit,
    tile: @Composable (T, Dp) -> Unit,
) {
    // Every tab stays composed: on screen is the current tab, with the tabs themselves showing.
    val onScreen = LocalTabIsCurrent.current && LocalTabsOnScreen.current
    val reduceMotion = LocalReduceMotion.current
    // True: tiles still tucked away (the pose the arrival starts from).
    var tucked by remember { mutableStateOf(StickerVisits.isDue(visitKey)) }
    // Each arrival bumps it; tiles spring in on their own delay when it changes.
    var arrival by remember { mutableIntStateOf(0) }

    LaunchedEffect(onScreen) {
        if (!onScreen) return@LaunchedEffect
        if (!tucked && !StickerVisits.isDue(visitKey)) return@LaunchedEffect
        // Recorded now, so switching away and back at once doesn't play it twice.
        StickerVisits.leave(visitKey)
        if (!reduceMotion) arrival++
        tucked = false
    }
    DisposableEffect(onScreen) {
        onDispose { if (onScreen) StickerVisits.leave(visitKey) }
    }

    // Each tile goes to the shorter column; heights run tall, short, short, tall so the two columns
    // never line up.
    val left = mutableListOf<Slot<T>>()
    val right = mutableListOf<Slot<T>>()
    var leftHeight = LeadWeight
    var rightHeight = 0.dp
    items.forEachIndexed { i, item ->
        val height = if (i % 4 == 0 || i % 4 == 3) LikesTileHeight.tall else LikesTileHeight.short
        val slot = Slot(item, height, minOf(i + 1, STAGGER_CAP))
        if (rightHeight <= leftHeight) {
            right += slot
            rightHeight += height + DS.Space.sm
        } else {
            left += slot
            leftHeight += height + DS.Space.sm
        }
    }

    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
            Box(Modifier.draftIn(0, tucked, arrival)) { lead() }
            left.forEach { slot ->
                key(itemKey(slot.item)) {
                    Box(Modifier.draftIn(slot.order, tucked, arrival)) { tile(slot.item, slot.height) }
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
            right.forEach { slot ->
                key(itemKey(slot.item)) {
                    Box(Modifier.draftIn(slot.order, tucked, arrival)) { tile(slot.item, slot.height) }
                }
            }
        }
    }
}

/** One tile filing in: from a little to the left and slightly smaller, faded, to its place. */
@Composable
private fun Modifier.draftIn(order: Int, tucked: Boolean, arrival: Int): Modifier {
    val progress = remember { Animatable(1f) }
    // The arrival this tile has started; a tile added later starts settled.
    var started by remember { mutableIntStateOf(arrival) }
    LaunchedEffect(arrival) {
        if (arrival == started) return@LaunchedEffect
        progress.snapTo(0f)
        started = arrival
        delay(order * 45L)
        progress.animateTo(1f, Motion.springOf(0.42, 0.8f))
    }
    val shift = with(LocalDensity.current) { 28.dp.toPx() }
    return graphicsLayer {
        val v = if (tucked || started != arrival) 0f else progress.value
        alpha = v
        translationX = -shift * (1 - v)
        scaleX = 0.94f + 0.06f * v
        scaleY = 0.94f + 0.06f * v
        transformOrigin = TransformOrigin(0f, 0.5f)
    }
}

// Tiles

/**
 * A like without drafft tempo: the server's ThumbHash of their first photo (`BlurredLike`, already a
 * blur, nothing sharper ever reaches the phone), a lock, and the red heart of a super like.
 */
@Composable
fun LockedLikeTile(like: BlurredLike, height: Dp, modifier: Modifier = Modifier) {
    val preview = remember(like.id) { like.preview?.let(::previewBitmap) }
    val shape = RoundedCornerShape(DS.Radius.xl)
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(DS.palette.night)
            .border(1.dp, DS.palette.blockEdge, shape),
    ) {
        if (preview != null) {
            Image(
                preview,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                // The hash is low-frequency: drawn smooth at tile size, it reads as frosted glass.
                filterQuality = FilterQuality.High,
            )
        }
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(DS.Space.md)
                .size(36.dp)
                .background(Color.White.copy(alpha = 0.2f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            DrafftIcon("lock-keyhole-minimalistic", size = 18.dp, tint = Color.White)
        }
        if (like.superLike) SuperLikeDisc(Modifier.align(Alignment.TopEnd).padding(DS.Space.sm))
    }
}

/**
 * A like with drafft tempo: their photo, name and age. The tile opens the profile; the green heart
 * likes them back at once (it's mutual from there).
 */
@Composable
fun LikeTile(profile: Profile, height: Dp, onOpen: () -> Unit, onLike: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(DS.Radius.xl)
    Box(modifier.fillMaxWidth()) {
        PressScaleButton(
            onClick = onOpen,
            modifier = Modifier.fillMaxWidth(),
            scale = 0.97f,
            contentDescription = L("%s, %d. Open profile", profile.name, profile.age),
        ) {
            Box(Modifier.fillMaxWidth().height(height).clip(shape)) {
                Photo(profile.portrait, Modifier.fillMaxSize(), side = 180.dp)
                Box(
                    Modifier
                        .fillMaxSize()
                        // design-lint: allow gradient - photo scrim under the name
                        .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to DS.palette.night.copy(alpha = 0.85f))),
                )
                ProfileIdentity(
                    profile = profile,
                    nameSize = 20f,
                    showsLocation = false,
                    showsSuperLike = true,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = DS.Space.md, end = 56.dp, bottom = DS.Space.md),
                )
            }
        }
        // Likes stay green whatever the brand accent.
        PressScaleButton(
            onClick = {
                Haptics.thump()
                onLike()
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(DS.Space.xxs).size(48.dp),
            scale = 0.86f,
            contentDescription = L("Like back"),
        ) {
            Box(
                Modifier.align(Alignment.Center).size(40.dp).background(DS.palette.like, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                DrafftIcon("heart", size = 20.dp, tint = DS.palette.onLike)
            }
        }
    }
}

/** The red disc of a super like, with its drafting heart. */
@Composable
private fun SuperLikeDisc(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(30.dp)
            .background(DS.palette.negative, CircleShape)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        SuperLikeMark(Modifier.padding(end = 6.dp), size = 11.dp, color = Color.White)
    }
}

// Lead blocks

/** The mosaic's first tile: a night block with the likes sign, a display line and one sentence. */
@Composable
fun LikesLeadBlock(headline: @Composable () -> Unit, message: AnnotatedString, modifier: Modifier = Modifier) {
    NightBlock(modifier.fillMaxWidth().heightIn(min = LikesTileHeight.tall)) {
        Column(Modifier.padding(DS.Space.lg).heightIn(min = LikesTileHeight.tall - DS.Space.lg * 2)) {
            Box(
                Modifier.size(44.dp).background(DS.palette.accentOnNight, CircleShape).clearAndSetSemantics { },
                contentAlignment = Alignment.Center,
            ) {
                DrafftIcon("user-heart", size = 22.dp, tint = DS.palette.onAccentOnNight)
            }
            Spacer(Modifier.weight(1f).heightIn(min = DS.Space.md))
            Box(Modifier.semantics { heading() }) { headline() }
            Spacer(Modifier.height(DS.Space.md))
            Text(message, style = TextStyles.subheadline, color = Color.White.copy(alpha = 0.72f))
        }
    }
}

/** The lead block's display line style. */
@Composable
internal fun leadHeadlineStyle() = display(26f)

/** With drafft tempo: who they are is right there, like back to match. */
@Composable
fun TempoLikesLead(modifier: Modifier = Modifier) {
    LikesLeadBlock(
        headline = { Text(L("They like you."), style = leadHeadlineStyle(), color = DS.palette.accentOnNight) },
        message = AnnotatedString(L("Like back and it's a match straight away.")),
        modifier = modifier,
    )
}

/** A ThumbHash's RGBA pixels (about 32 × 32) as a bitmap, made once per like. */
private fun previewBitmap(image: ThumbHash.Image): ImageBitmap {
    val bitmap = ImageBitmap(image.width, image.height)
    val canvas = Canvas(bitmap)
    val paint = Paint()
    val rgba = image.rgba
    for (y in 0 until image.height) {
        for (x in 0 until image.width) {
            val i = (y * image.width + x) * 4
            paint.color = Color(
                red = rgba[i].toInt() and 0xFF,
                green = rgba[i + 1].toInt() and 0xFF,
                blue = rgba[i + 2].toInt() and 0xFF,
                alpha = rgba[i + 3].toInt() and 0xFF,
            )
            canvas.drawRect(x.toFloat(), y.toFloat(), x + 1f, y + 1f, paint)
        }
    }
    return bitmap
}
