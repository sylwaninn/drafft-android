package so.drafft.app.feature.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import kotlinx.coroutines.delay
import so.drafft.app.feature.discover.ProfileIdentity
import so.drafft.core.data.BlurredLike
import so.drafft.core.data.platform.Haptics
import so.drafft.core.model.L
import so.drafft.core.model.LikeAge
import so.drafft.core.model.Profile
import so.drafft.core.model.ThumbHash
import so.drafft.core.ui.components.EmptyStateArt
import so.drafft.core.ui.components.LocalTabIsCurrent
import so.drafft.core.ui.components.LocalTabsOnScreen
import so.drafft.core.ui.components.NightBlock
import so.drafft.core.ui.components.Photo
import so.drafft.core.ui.components.PressScaleButton
import so.drafft.core.ui.components.RollingText
import so.drafft.core.ui.components.StickerVisits
import so.drafft.core.ui.components.StillSticker
import so.drafft.core.ui.components.SuperLikeMark
import so.drafft.core.ui.components.rememberPhotoRequest
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.LocalReduceMotion
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.branded
import so.drafft.core.ui.theme.display
import so.drafft.core.ui.theme.semibold
import java.time.Instant

/** Tiles past this one arrive together with it: a long list doesn't make you wait. */
private const val STAGGER_CAP = 9

/** The one tile shape: 3:4 portrait, so every row of the grid is the same height. */
private const val TILE_ASPECT = 3f / 4f

/**
 * The Likes grid: a banner (how many like you, and what to do) above a regular grid of portrait
 * tiles, two columns, every tile the same 3:4 shape so all rows line up. Only people go in the grid;
 * the words live in the banner. Used by the Likes tab and the drafft tempo likes sheet, blurred or
 * sharp.
 *
 * Arriving on it after a while (`StickerVisits`, the same 30 s rule as the empty-tab sticker) files
 * the banner and the tiles in from the left, one after the other, like riders tucking into a draft.
 * A quick round of tabs or the walk under the splash doesn't replay it. Reduce Motion: already in
 * place.
 */
@Composable
fun <T> LikesGrid(
    items: List<T>,
    itemKey: (T) -> Any,
    visitKey: String,
    modifier: Modifier = Modifier,
    banner: @Composable () -> Unit,
    tile: @Composable (T, Instant) -> Unit,
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

    // One clock for every tile: the age labels stay true while the screen is open.
    val now = rememberMinuteClock()

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DS.Space.md)) {
        Box(Modifier.draftIn(0, tucked, arrival)) { banner() }
        // Inside the page's scroll: rows of two, each tile the same shape (a lazy grid can't nest here).
        Column(verticalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
            items.chunked(2).forEachIndexed { row, pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
                    pair.forEachIndexed { column, item ->
                        val order = minOf(row * 2 + column + 1, STAGGER_CAP)
                        key(itemKey(item)) {
                            Box(Modifier.weight(1f).draftIn(order, tucked, arrival)) { tile(item, now) }
                        }
                    }
                    // A lone last tile keeps its column's width.
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * The time now, read again at each minute: one state write a minute
 * for the whole grid.
 */
@Composable
private fun rememberMinuteClock(): Instant {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000 - (System.currentTimeMillis() % 60_000))
            now = Instant.now()
        }
    }
    return now
}

/** One element filing in: from a little to the left and slightly smaller, faded, to its place. */
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
 * "5 min ago": a small translucent night capsule at the top of a tile. Night at 45 % so white text
 * reads over a blurred photo and a sharp one alike; one line, never cut.
 */
@Composable
private fun LikeAgeLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier
            .background(DS.palette.night.copy(alpha = 0.45f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .clearAndSetSemantics { },
        style = TextStyles.caption.semibold,
        color = Color.White,
        maxLines = 1,
        softWrap = false,
    )
}

/**
 * The one tile frame: 3:4 portrait, whatever the screen width, so every row of the grid is the same
 * height. Content fills it; nothing in it sizes it.
 */
@Composable
private fun PortraitFrame(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth().aspectRatio(TILE_ASPECT).clip(RoundedCornerShape(DS.Radius.xl))) { content() }
}

/**
 * A like without drafft tempo. The server's blurred copy of their first photo ([BlurredLike.blurUrl],
 * a signed link) once it's loaded; until then, or if there is none or it fails, the ThumbHash. Both
 * are blurs made on the server: nothing sharper ever reaches the phone. A lock, and the red heart of
 * a super like.
 */
@Composable
fun LockedLikeTile(like: BlurredLike, now: Instant, modifier: Modifier = Modifier) {
    val preview = remember(like.id) { like.preview?.let(::previewBitmap) }
    val shape = RoundedCornerShape(DS.Radius.xl)
    Box(modifier.fillMaxWidth().border(1.dp, DS.palette.blockEdge, shape)) {
        PortraitFrame {
            Box(Modifier.fillMaxSize().background(DS.palette.night))
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
            like.blurUrl?.let { ServerBlurredPhoto(it) }
            // One even veil so every tile reads alike and the lock stands out on a pale photo.
            Box(Modifier.fillMaxSize().background(DS.palette.night.copy(alpha = 0.16f)))
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
        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth().padding(DS.Space.sm),
            horizontalArrangement = Arrangement.spacedBy(DS.Space.xs),
            verticalAlignment = Alignment.Top,
        ) {
            LikeAge.text(like.likedAt, now)?.let { LikeAgeLabel(it) }
            Spacer(Modifier.weight(1f))
            if (like.superLike) SuperLikeDisc()
        }
    }
}

/**
 * The server's blurred rendition, through the app's image pipeline (Coil: decoded at tile size in the
 * background, memory and disk caches keyed by the object, never by the signature). Softened a little
 * more at decode so it matches the ThumbHash it replaces. Transparent until it's there, so the
 * ThumbHash under it stays as the placeholder and the fallback.
 */
@Composable
private fun ServerBlurredPhoto(url: String) {
    BoxWithConstraints(Modifier.fillMaxSize().clearAndSetSemantics { }) {
        val width = constraints.maxWidth.takeIf { it in 1..<Int.MAX_VALUE } ?: 540
        val height = constraints.maxHeight.takeIf { it in 1..<Int.MAX_VALUE } ?: 720
        val request = rememberPhotoRequest(url, width, height, blur = 0.03f, variant = "blurred") ?: return@BoxWithConstraints
        val painter = rememberAsyncImagePainter(request, contentScale = ContentScale.Crop)
        val state by painter.state.collectAsState()
        val ready = state is AsyncImagePainter.State.Success
        val readyAtFirstFrame = remember(request) { ready }
        val alpha by animateFloatAsState(
            if (ready) 1f else 0f,
            if (readyAtFirstFrame) tween(0) else tween(250, easing = Motion.EaseOut),
            label = "blurFade",
        )
        Image(
            painter,
            contentDescription = null,
            modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha },
            contentScale = ContentScale.Crop,
        )
    }
}

/**
 * A like with drafft tempo: their photo, name and age. The tile opens the profile; the green heart
 * likes them back at once (it's mutual from there).
 */
@Composable
fun LikeTile(profile: Profile, now: Instant, onOpen: () -> Unit, onLike: () -> Unit, modifier: Modifier = Modifier) {
    val age = LikeAge.text(profile.likedAt, now)
    Box(modifier.fillMaxWidth()) {
        PressScaleButton(
            onClick = onOpen,
            modifier = Modifier.fillMaxWidth(),
            scale = 0.97f,
            contentDescription = listOfNotNull(L("%s, %d. Open profile", profile.name, profile.age), age).joinToString(", "),
        ) {
            PortraitFrame {
                Photo(profile.portrait, Modifier.fillMaxSize(), side = 240.dp)
                Box(
                    Modifier
                        .fillMaxSize()
                        // design-lint: allow gradient - photo scrim under the name
                        .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to DS.palette.night.copy(alpha = 0.85f))),
                )
                Box(Modifier.fillMaxSize()) {
                    if (age != null) LikeAgeLabel(age, Modifier.align(Alignment.TopStart).padding(DS.Space.sm))
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

/** The red disc of a super like, with its heart. */
@Composable
private fun SuperLikeDisc(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(30.dp)
            .background(DS.palette.negative, CircleShape)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        SuperLikeMark(size = 11.dp, color = Color.White)
    }
}

// Banner

/**
 * The top of Likes: a night banner with the likes sticker (the empty tab's, still), how many people like you (counted from the
 * list the server sent, never a made-up figure) and one sentence on what to do. It holds no button:
 * the screen's one action is pinned at the bottom (or on each tile with drafft tempo).
 */
@Composable
fun LikesBanner(count: Int, message: AnnotatedString, modifier: Modifier = Modifier) {
    NightBlock(modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(DS.Space.lg).semantics(mergeDescendants = true) { },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DS.Space.md),
        ) {
            StillSticker(EmptyStateArt.likes)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DS.Space.xs)) {
                RollingText(
                    if (count == 1) L("1 person likes you.") else L("%d people like you.", count),
                    modifier = Modifier.semantics { heading() },
                    style = display(22f),
                    color = DS.palette.accentOnNight,
                )
                Text(message, style = TextStyles.subheadline, color = Color.White.copy(alpha = 0.72f))
            }
        }
    }
}

/** Without drafft tempo: what drafft tempo does about it. The pinned button opens it. */
@Composable
fun LockedLikesBanner(count: Int, modifier: Modifier = Modifier) {
    LikesBanner(
        count = count,
        message = branded(L("See who, and match in one tap with drafft tempo."), FontWeight.SemiBold, tierColor = DS.palette.tierOnNight),
        modifier = modifier,
    )
}

/** With drafft tempo: who they are is right there, like back to match. */
@Composable
fun TempoLikesBanner(count: Int, modifier: Modifier = Modifier) {
    LikesBanner(count = count, message = AnnotatedString(L("Like them back and it's mutual. Then propose a session.")), modifier = modifier)
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
