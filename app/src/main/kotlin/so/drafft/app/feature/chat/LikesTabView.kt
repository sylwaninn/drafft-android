package so.drafft.app.feature.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import so.drafft.app.feature.me.PaywallView
import so.drafft.core.data.AppModel
import so.drafft.core.data.BlurredLike
import so.drafft.core.data.platform.Haptics
import so.drafft.core.model.L
import so.drafft.core.model.Profile
import so.drafft.core.model.ThumbHash
import so.drafft.core.ui.LocalAppModel
import so.drafft.core.ui.components.DrafftButton
import so.drafft.core.ui.components.DrafftSheet
import so.drafft.core.ui.components.EmptyStateArt
import so.drafft.core.ui.components.EmptyStateView
import so.drafft.core.ui.components.LocalTabBarInset
import so.drafft.core.ui.components.NightBlock
import so.drafft.core.ui.components.PressScaleButton
import so.drafft.core.ui.components.TabHeader
import so.drafft.core.ui.components.TabTitle
import so.drafft.core.ui.components.TopBar
import so.drafft.core.ui.components.draftTrail
import so.drafft.core.ui.components.trackingScrollOffset
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.branded
import so.drafft.core.ui.theme.display

// Port of Drafft/Features/Chat/LikesTabView.swift.

/**
 * Likes tab: everyone who already liked you, as a grid of cards. With drafft tempo, open a profile and
 * like back to match. Without it the server sends no identity, only a ThumbHash per like
 * (`AppModel.blurredLikes`): the grid shows those previews and a night block offers the unlock.
 */
@Composable
fun LikesTabView(modifier: Modifier = Modifier) {
    val app = LocalAppModel.current
    val scroll = rememberScrollState()
    val offset by scroll.trackingScrollOffset()
    var open by remember { mutableStateOf<Profile?>(null) }
    var showPaywall by remember { mutableStateOf(false) }

    // Live afterwards through the `wallet` event and each reconnection (`UserChannel`).
    LaunchedEffect(Unit) { app.loadLikes() }

    BoxWithConstraints(modifier.fillMaxSize().background(DS.palette.canvasSoft)) {
        val pageHeight = maxHeight
        TopBar(
            scroll = scroll,
            bar = { TabHeader(offset = { offset }) { TabTitle(L("Likes")) } },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(padding)
                    .padding(top = DS.Space.xs)
                    .padding(horizontal = DS.Space.lg)
                    .padding(bottom = DS.Space.xl + LocalTabBarInset.current),
                verticalArrangement = Arrangement.spacedBy(DS.Space.md),
            ) {
                val empty: @Composable () -> Unit = {
                    // The middle of the visible page, under the header.
                    Box(Modifier.fillMaxWidth().height(pageHeight * 0.8f), contentAlignment = Alignment.Center) {
                        EmptyStateView(
                            art = EmptyStateArt.likes,
                            title = L("No likes yet."),
                            message = L("A sport photo and a voice intro help. New likes land here."),
                        ) {
                            DrafftButton(L("Back to Discover"), onClick = { app.tab = AppModel.Tab.DISCOVER }, fullWidth = false)
                        }
                    }
                }
                when {
                    app.isPremium -> if (app.likedMe.isEmpty()) {
                        empty()
                    } else {
                        LikesGrid(app.likedMe) { p ->
                            Haptics.tap()
                            open = p
                        }
                    }
                    app.blurredLikes.isEmpty() -> empty()
                    else -> {
                        UnlockBlock(app.blurredLikes.size) {
                            Haptics.tap()
                            showPaywall = true
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
                            app.blurredLikes.chunked(2).forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
                                    row.forEach { like ->
                                        PressScaleButton(
                                            onClick = {
                                                Haptics.tap()
                                                showPaywall = true
                                            },
                                            modifier = Modifier.weight(1f),
                                            scale = 0.97f,
                                            contentDescription = L("Someone who likes you. Unlock with drafft tempo"),
                                        ) { BlurredCard(like) }
                                    }
                                    if (row.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Same presentation and actions as a profile opened from Discover.
    LikedProfileSheet(open) { open = null }
    DrafftSheet(visible = showPaywall, onDismissRequest = { showPaywall = false }) {
        PaywallView(
            headline = L("See who likes you."),
            pitch = L("drafft tempo shows everyone who already liked you, so you can match in one tap."),
            unlockedTitle = L("See who likes you"),
        )
    }
}

@Composable
private fun UnlockBlock(count: Int, onUnlock: () -> Unit) {
    NightBlock(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(DS.Space.xl), verticalArrangement = Arrangement.spacedBy(DS.Space.md)) {
            Text(
                if (count == 1) L("1 person likes you.") else L("%d people like you.", count),
                Modifier.semantics { heading() },
                style = display(30f),
                color = DS.palette.accentOnNight,
            )
            Text(
                branded(L("See who, and match in one tap with drafft tempo."), FontWeight.SemiBold, tierColor = DS.palette.accentOnNight),
                style = TextStyles.subheadline,
                color = Color.White.copy(alpha = 0.72f),
            )
            DrafftButton(
                L("See who likes you"),
                onClick = onUnlock,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .draftTrail(RoundedCornerShape(DS.Radius.xl), step = DpOffset((-6).dp, 0.dp)),
            )
        }
    }
}

/**
 * A like on the free plan: the server's ThumbHash (already a blur), a lock, and a star for a
 * super like.
 */
@Composable
private fun BlurredCard(like: BlurredLike) {
    val preview = remember(like.id) { like.preview?.let(::previewBitmap) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(230.dp)
            .clip(RoundedCornerShape(DS.Radius.xl))
            .background(DS.palette.sage),
    ) {
        if (preview != null) {
            Image(
                preview,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Medium,
            )
        }
        Box(
            Modifier
                .align(Alignment.Center)
                .size(48.dp)
                .background(Color.White.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            DrafftIcon("lock-keyhole-minimalistic", size = 24.dp, tint = Color.White)
        }
        if (like.superLike) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(DS.Space.sm)
                    .size(30.dp)
                    .background(DS.palette.accentOnNight, CircleShape)
                    .clearAndSetSemantics { },
                contentAlignment = Alignment.Center,
            ) {
                DrafftIcon("star", size = 16.dp, tint = DS.palette.onAccentOnNight)
            }
        }
    }
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
