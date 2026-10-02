package so.drafft.app.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import so.drafft.core.data.media.MediaURL
import so.drafft.core.data.telemetry.AnalyticsEvent
import so.drafft.core.data.telemetry.Screen
import so.drafft.core.data.telemetry.Telemetry
import so.drafft.core.model.Conversation
import so.drafft.core.model.L
import so.drafft.core.model.MessageContent
import so.drafft.core.ui.TrackScreen
import so.drafft.core.ui.components.MessageImage
import so.drafft.core.ui.components.pressScale
import so.drafft.core.ui.components.rememberPhotoRequest
import so.drafft.core.ui.image.BundledImages
import so.drafft.core.ui.platform.LocalPlatformUi
import so.drafft.core.ui.platform.ShareItem
import so.drafft.core.ui.platform.VideoPlayer
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.monospacedDigits
import so.drafft.core.ui.theme.semibold
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.launch

// Port of Drafft/Features/Chat/MediaViewer.swift.

/** A photo, video or file from a chat, opened full screen. */
data class MediaItem(
    /** The message it belongs to. */
    val id: String,
    val kind: Kind,
) {
    sealed interface Kind {
        class Photo(val asset: String?, val data: ByteArray?) : Kind {
            override fun equals(other: Any?) = other is Photo && other.asset == asset &&
                (other.data === data || (other.data != null && data != null && other.data.contentEquals(data)))
            override fun hashCode() = asset.hashCode() * 31 + (data?.contentHashCode() ?: 0)
        }

        data class Video(val url: String) : Kind
        data class File(val url: String) : Kind
    }

    companion object {
        /** Every photo and video of a conversation, in order: what the viewer swipes through. */
        fun gallery(convo: Conversation): List<MediaItem> = convo.messages.mapNotNull { m ->
            when (val c = m.content) {
                is MessageContent.Photo -> MediaItem(m.id, Kind.Photo(c.asset, c.imageData))
                is MessageContent.Video -> MediaItem(m.id, Kind.Video(c.url))
                else -> null
            }
        }
    }
}

/**
 * Full-screen viewer: swipe between the chat's photos and videos, pinch or double-tap to zoom (and pan
 * while zoomed), swipe down to close when not zoomed, share. Videos play in the design system's
 * player. A file opens in the app that handles it (the iPhone shows it in Quick Look).
 */
@Composable
fun MediaViewer(
    items: List<MediaItem>,
    start: MediaItem,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TrackScreen(Screen.MEDIA_VIEWER)
    val shown = remember(items, start) { if (items.any { it.id == start.id }) items else listOf(start) }
    val pager = rememberPagerState(initialPage = shown.indexOfFirst { it.id == start.id }.coerceAtLeast(0)) { shown.size }
    var zoomed by remember { mutableStateOf(false) }
    var chromeHidden by remember { mutableStateOf(false) }
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val dismiss by rememberUpdatedState(onDismiss)
    val uriHandler = LocalUriHandler.current

    // A file alone: handed to the app that opens it.
    val file = (shown.singleOrNull()?.kind as? MediaItem.Kind.File)?.url
    if (file != null) {
        LaunchedEffect(file) {
            runCatching { uriHandler.openUri(file) }
            dismiss()
        }
        return
    }

    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { zoomed = false } }

    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 1f - min(0.7f, abs(drag.value) / density.density / 350f) }
                .background(Color.Black),
        )
        HorizontalPager(
            state = pager,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = drag.value
                    val s = 1f - min(0.12f, abs(drag.value) / density.density / 2500f)
                    scaleX = s
                    scaleY = s
                }
                // Vertical swipe to close; horizontal swipes page, and it's off while zoomed.
                .pointerInput(zoomed) {
                    if (zoomed) return@pointerInput
                    val slop = 20.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val velocity = VelocityTracker()
                        var total = Offset.Zero
                        var dragging = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            if (event.changes.size > 1) return@awaitEachGesture
                            velocity.addPosition(change.uptimeMillis, change.position)
                            total += change.positionChange()
                            if (!dragging) {
                                if (change.isConsumed) return@awaitEachGesture
                                // Only a clearly vertical drag: horizontal ones belong to paging.
                                if (abs(total.y) > slop && abs(total.y) > abs(total.x) * 1.4f) dragging = true else continue
                            }
                            change.consume()
                            scope.launch { drag.snapTo(total.y) }
                        }
                        if (!dragging) return@awaitEachGesture
                        val v = velocity.calculateVelocity().y / density.density
                        if (abs(drag.value) / density.density > 120f || abs(v) > 1000f) {
                            dismiss()
                        } else {
                            scope.launch { drag.animateTo(0f, Motion.snappy()) }
                        }
                    }
                },
            beyondViewportPageCount = 1,
            key = { shown[it].id },
        ) { page ->
            val item = shown[page]
            MediaPage(
                item = item,
                playing = pager.currentPage == page,
                zoomed = zoomed && pager.currentPage == page,
                onZoomChange = { if (pager.currentPage == page) zoomed = it },
                onSingleTap = { chromeHidden = !chromeHidden },
            )
        }

        AnimatedVisibility(
            visible = !chromeHidden,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(Motion.snappy()),
            exit = fadeOut(Motion.snappy()),
        ) {
            TopChrome(shown, pager.currentPage, onClose = { dismiss() })
        }
    }
}

@Composable
private fun MediaPage(
    item: MediaItem,
    playing: Boolean,
    zoomed: Boolean,
    onZoomChange: (Boolean) -> Unit,
    onSingleTap: () -> Unit,
) {
    when (val kind = item.kind) {
        is MediaItem.Kind.Photo -> {
            val context = LocalPlatformContext.current
            val asset = kind.asset
            val data = kind.data
            val photo = L("Photo")
            val bundled = asset?.let(BundledImages::resource)
            val loadable = data != null || bundled != null || asset?.startsWith("http") == true || asset?.startsWith("/") == true
            if (!loadable) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    DrafftIcon("gallery", size = 40.dp, tint = Color.White.copy(alpha = 0.5f))
                }
                return
            }
            ZoomableImage(zoomed, onZoomChange, onSingleTap, Modifier.semantics { contentDescription = photo }) {
                when {
                    data != null -> {
                        val request = remember(item.id) { MessageImage.request(context, item.id, data, 2048) }
                        AsyncImage(request, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    }
                    bundled != null -> Image(painterResource(bundled), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    asset != null -> {
                        // A photo sent in a chat, from the media bucket (through the shared image pipeline and its caches).
                        val request = rememberPhotoRequest(asset, 2048, 2048, fill = false)
                        if (request != null) {
                            AsyncImage(request, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        }
                    }
                }
            }
        }
        is MediaItem.Kind.Video -> FreshVideo(kind.url, playing)
        is MediaItem.Kind.File -> {
            val uriHandler = LocalUriHandler.current
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(kind.url) { detectTapGestures { runCatching { uriHandler.openUri(kind.url) } } },
                contentAlignment = Alignment.Center,
            ) {
                DrafftIcon("file", size = 56.dp, tint = Color.White.copy(alpha = 0.7f))
            }
        }
    }
}

/**
 * Zoom like Photos: pinch, pan while zoomed, double tap zooms on the tapped point (and back). A single
 * tap shows or hides the viewer's controls. Not zoomed, one-finger drags are left to paging and to the
 * swipe that closes. [zoomed] false from outside (another page) zooms back out.
 */
@Composable
private fun ZoomableImage(
    zoomed: Boolean,
    onZoomChange: (Boolean) -> Unit,
    onSingleTap: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scale = remember { Animatable(1f) }
    val pan = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val scope = rememberCoroutineScope()
    var size by remember { mutableStateOf(IntSize.Zero) }
    val zoomChange by rememberUpdatedState(onZoomChange)
    val tap by rememberUpdatedState(onSingleTap)

    fun clamp(offset: Offset, s: Float): Offset {
        val maxX = (s - 1f) * size.width / 2f
        val maxY = (s - 1f) * size.height / 2f
        return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
    }

    LaunchedEffect(zoomed) {
        if (!zoomed && scale.value != 1f) {
            launch { scale.animateTo(1f, Motion.snappy()) }
            pan.animateTo(Offset.Zero, Motion.snappy())
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { tap() },
                    onDoubleTap = { point ->
                        scope.launch {
                            if (scale.value > 1.01f) {
                                launch { pan.animateTo(Offset.Zero, Motion.snappy()) }
                                scale.animateTo(1f, Motion.snappy())
                                zoomChange(false)
                            } else {
                                val target = 2.5f
                                val center = Offset(size.width / 2f, size.height / 2f)
                                launch { pan.animateTo(clamp((center - point) * (target - 1f), target), Motion.snappy()) }
                                scale.animateTo(target, Motion.snappy())
                                zoomChange(true)
                            }
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) break
                        val pinching = event.changes.count { it.pressed } > 1
                        // One finger, not zoomed: paging and the close swipe take it.
                        if (!pinching && scale.value <= 1.01f) continue
                        val zoom = event.calculateZoom()
                        val move = event.calculatePan()
                        val centroid = event.calculateCentroid(useCurrent = true)
                        val s = (scale.value * zoom).coerceIn(1f, 4f)
                        val center = Offset(size.width / 2f, size.height / 2f)
                        // The point under the fingers stays under them.
                        val c = centroid - center
                        val next = clamp(c - (c - pan.value) * (s / scale.value) + move, s)
                        scope.launch {
                            scale.snapTo(s)
                            pan.snapTo(next)
                        }
                        zoomChange(s > 1.01f)
                        event.changes.forEach { if (it.positionChange() != Offset.Zero) it.consume() }
                    }
                }
            }
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                translationX = pan.value.x
                translationY = pan.value.y
            },
    ) { content() }
}

/** A chat video: its signed link renewed first if it's about to expire (the thread may have been open a while). */
@Composable
private fun FreshVideo(url: String, playing: Boolean) {
    val fresh by produceState<String?>(null, url) {
        value = if (url.startsWith("http")) MediaURL.fresh(url) else url
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        fresh?.let {
            VideoPlayer(it, Modifier.fillMaxSize(), autoplay = playing, muted = false, loop = false, contentScale = ContentScale.Fit)
        }
    }
}

/** Glass on the black viewer: a light translucent fill with a hairline, white glyphs. */
private fun Modifier.darkGlass(shape: Shape): Modifier =
    background(Color.White.copy(alpha = 0.16f), shape).border(0.5.dp, Color.White.copy(alpha = 0.2f), shape)

@Composable
private fun TopChrome(items: List<MediaItem>, index: Int, onClose: () -> Unit) {
    val share = LocalPlatformUi.current.rememberShare()
    val current = items.getOrNull(index)
    val shareable: ShareItem? = when (val kind = current?.kind) {
        is MediaItem.Kind.Photo -> kind.data?.let { ShareItem.Image(it) }
        is MediaItem.Kind.Video -> ShareItem.Video(kind.url)
        else -> null
    }
    Row(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = DS.Space.lg)
            .padding(top = DS.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChromeButton("close", L("Close"), onClose)
        Spacer(Modifier.weight(1f))
        if (items.size > 1) {
            Box(
                Modifier
                    .height(32.dp)
                    .darkGlass(RoundedCornerShape(50))
                    .padding(horizontal = DS.Space.md),
                contentAlignment = Alignment.Center,
            ) {
                Text(L("%d of %d", index + 1, items.size), style = TextStyles.subheadline.semibold.monospacedDigits, color = Color.White)
            }
        }
        Spacer(Modifier.weight(1f))
        if (shareable != null) {
            ChromeButton("upload-minimalistic", L("Share")) {
                Telemetry.track(AnalyticsEvent.ShareTapped(if (shareable is ShareItem.Video) "video" else "photo"))
                share(shareable)
            }
        } else {
            Spacer(Modifier.size(44.dp))
        }
    }
}

@Composable
private fun ChromeButton(symbol: String, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .pressScale(onClick)
            .semantics { contentDescription = label }
            .darkGlass(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        DrafftIcon(symbol, size = 20.dp, tint = Color.White)
    }
}
