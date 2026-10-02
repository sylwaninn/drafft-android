package so.drafft.app.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import so.drafft.app.feature.me.PaywallView
import so.drafft.core.data.AppModel
import so.drafft.core.data.platform.Haptics
import so.drafft.core.data.telemetry.AnalyticsEvent
import so.drafft.core.data.telemetry.Telemetry
import so.drafft.core.model.L
import so.drafft.core.model.LikeAge
import so.drafft.core.model.Profile
import so.drafft.core.ui.LocalAppModel
import so.drafft.core.ui.components.DrafftButton
import so.drafft.core.ui.components.DrafftSheet
import so.drafft.core.ui.components.EdgeBars
import so.drafft.core.ui.components.EmptyStateArt
import so.drafft.core.ui.components.EmptyStateView
import so.drafft.core.ui.components.ListLoadFailureView
import so.drafft.core.ui.components.LocalTabBarInset
import so.drafft.core.ui.components.LocalTabIsCurrent
import so.drafft.core.ui.components.PressScaleButton
import so.drafft.core.ui.components.TabHeader
import so.drafft.core.ui.components.TabTitle
import so.drafft.core.ui.components.draftTrail
import so.drafft.core.ui.components.trackingScrollOffset
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon

/**
 * Likes tab: a banner with how many people like you, then everyone who already liked you as a grid of
 * equal portraits (`LikesGrid`).
 *
 * - Without drafft tempo the server sends no identity, only blurred previews per like
 *   (`AppModel.blurredLikes`: a ThumbHash, and a blurred copy of the photo when the backend has one):
 *   the tiles are those previews, the banner counts them, and the one action, pinned at the bottom,
 *   opens the paywall (so does any tile).
 * - With drafft tempo the tiles are their photos: open a profile, or like back right from the tile.
 */
@Composable
fun LikesTabView(modifier: Modifier = Modifier) {
    val app = LocalAppModel.current
    val scroll = rememberScrollState()
    val offset by scroll.trackingScrollOffset()
    var open by remember { mutableStateOf<Profile?>(null) }
    var showPaywall by remember { mutableStateOf(false) }
    val locked = !app.isPremium && app.blurredLikes.isNotEmpty()
    val tabBar = LocalTabBarInset.current
    val scope = rememberCoroutineScope()

    // Live afterwards through the `like` and `wallet` events and each reconnection (`UserChannel`).
    LaunchedEffect(Unit) { app.loadLikes() }
    // Each time the tab is shown: how many likes waited, and whether they could be seen.
    val onScreen = LocalTabIsCurrent.current
    LaunchedEffect(onScreen) {
        if (onScreen) Telemetry.track(AnalyticsEvent.LikesViewed(count = app.likedMeCount, premium = app.isPremium))
    }

    BoxWithConstraints(modifier.fillMaxSize().background(DS.palette.canvasSoft)) {
        val pageHeight = maxHeight
        EdgeBars(
            scroll = scroll,
            topBar = { TabHeader(offset = { offset }) { TabTitle(L("Likes")) } },
            bottomBar = {
                // The one action without drafft tempo, always on screen above the tab bar.
                AnimatedVisibility(
                    visible = locked,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                ) {
                    UnlockButton(Modifier.padding(bottom = tabBar)) {
                        Haptics.tap()
                        showPaywall = true
                    }
                }
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(padding)
                    .padding(top = DS.Space.xs)
                    .padding(horizontal = DS.Space.lg)
                    .padding(bottom = DS.Space.xl + if (locked) 0.dp else tabBar),
            ) {
                // Nobody yet only once the list was read: before, a spinner; after a failed first read, a retry.
                val empty: @Composable () -> Unit = {
                    // The middle of the visible page, under the header.
                    Box(Modifier.fillMaxWidth().height(pageHeight * 0.8f), contentAlignment = Alignment.Center) {
                        when (val load = app.likesLoad) {
                            AppModel.ListLoad.Loaded -> EmptyStateView(
                                art = EmptyStateArt.likes,
                                title = L("No likes yet."),
                                message = L("A sport photo and a voice intro help. New likes land here."),
                            ) {
                                DrafftButton(L("Back to Discover"), onClick = { app.tab = AppModel.Tab.DISCOVER }, fullWidth = false)
                            }
                            is AppModel.ListLoad.Failed -> ListLoadFailureView(
                                art = EmptyStateArt.likes,
                                title = L("Your likes couldn't load"),
                                offline = load.offline,
                                retry = {
                                    app.likesLoad = AppModel.ListLoad.Loading
                                    scope.launch { app.loadLikes() }
                                },
                            )
                            AppModel.ListLoad.Loading -> CircularProgressIndicator(color = DS.palette.ink)
                        }
                    }
                }
                when {
                    app.isPremium -> if (app.likedMe.isEmpty()) {
                        empty()
                    } else {
                        LikesGrid(
                            items = app.likedMe,
                            itemKey = { it.id },
                            visitKey = "likes-tab",
                            banner = { TempoLikesBanner(app.likedMe.size) },
                        ) { p, now ->
                            LikeTile(
                                profile = p,
                                now = now,
                                onOpen = {
                                    Haptics.tap()
                                    open = p
                                },
                                onLike = { app.swipe(p, liked = true) },
                            )
                        }
                    }
                    app.blurredLikes.isEmpty() -> empty()
                    else -> LikesGrid(
                        items = app.blurredLikes,
                        itemKey = { it.id },
                        visitKey = "likes-tab",
                        banner = { LockedLikesBanner(app.blurredLikes.size) },
                    ) { like, now ->
                        PressScaleButton(
                            onClick = {
                                Haptics.tap()
                                showPaywall = true
                            },
                            scale = 0.97f,
                            contentDescription = listOfNotNull(
                                if (like.superLike) {
                                    L("Someone super liked you. Unlock with drafft tempo")
                                } else {
                                    L("Someone who likes you. Unlock with drafft tempo")
                                },
                                LikeAge.text(like.likedAt, now),
                            ).joinToString(", "),
                        ) { LockedLikeTile(like, now) }
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
private fun UnlockButton(modifier: Modifier = Modifier, onUnlock: () -> Unit) {
    DrafftButton(
        onClick = onUnlock,
        modifier = modifier
            .padding(horizontal = DS.Space.lg, vertical = DS.Space.md)
            .padding(start = 12.dp)
            .draftTrail(RoundedCornerShape(DS.Radius.xl), step = DpOffset((-6).dp, 0.dp)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DrafftIcon("user-heart", Modifier.padding(end = DS.Space.sm), size = 20.dp, tint = DS.palette.onLime)
            Text(L("See who likes you"), maxLines = 2)
        }
    }
}
