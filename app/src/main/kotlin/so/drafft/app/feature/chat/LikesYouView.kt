package so.drafft.app.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import so.drafft.app.feature.profile.ProfileDetailMode
import so.drafft.app.feature.profile.ProfileDetailView
import so.drafft.core.data.platform.Haptics
import so.drafft.core.model.L
import so.drafft.core.model.Profile
import so.drafft.core.ui.LocalAppModel
import so.drafft.core.ui.components.BlurredNavigationEdge
import so.drafft.core.ui.components.DrafftSheet
import so.drafft.core.ui.components.SheetNavBar
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.TextStyles

// Port of Drafft/Features/Chat/LikesYouView.swift.

/**
 * drafft tempo: everyone who already liked you, as the Likes grid (`LikesGrid`) under its banner.
 * Like back and it's mutual right away. Shown in a sheet (`DrafftSheet`); Close is its own (`LocalSheetDismiss`).
 */
@Composable
fun LikesYouView(modifier: Modifier = Modifier) {
    val app = LocalAppModel.current
    var open by remember { mutableStateOf<Profile?>(null) }
    val scroll = rememberScrollState()

    BlurredNavigationEdge(
        scroll = scroll,
        modifier = modifier.fillMaxSize().background(DS.palette.canvasSoft),
        navigationBar = { SheetNavBar(title = L("Likes")) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(padding)
                .padding(horizontal = DS.Space.lg)
                .padding(bottom = DS.Space.xl),
            verticalArrangement = Arrangement.spacedBy(DS.Space.md),
        ) {
            if (app.likedMe.isEmpty()) {
                Text(
                    L("No new likes right now. Keep swiping, they'll show up here."),
                    Modifier
                        .fillMaxWidth()
                        .background(DS.palette.canvas, RoundedCornerShape(DS.Radius.xl))
                        .padding(DS.Space.xl),
                    style = TextStyles.body,
                    color = DS.palette.body,
                )
            } else {
                LikesGrid(
                    items = app.likedMe,
                    itemKey = { it.id },
                    visitKey = "likes-sheet",
                    banner = { TempoLikesBanner(app.likedMe.size) },
                ) { p ->
                    LikeTile(
                        profile = p,
                        onOpen = {
                            Haptics.tap()
                            open = p
                        },
                        onLike = { app.swipe(p, liked = true) },
                    )
                }
            }
        }
    }

    // Same presentation and actions as a profile opened from Discover.
    LikedProfileSheet(open) { open = null }
}

/** A liked-you profile opened in a sheet: like or pass (or super like) as on Discover. */
@Composable
internal fun LikedProfileSheet(open: Profile?, onClose: () -> Unit) {
    val app = LocalAppModel.current
    // Kept while the sheet slides down.
    val shown = remember { arrayOfNulls<Profile>(1) }
    if (open != null) shown[0] = open
    DrafftSheet(visible = open != null, onDismissRequest = onClose, showsGrabber = false, drawsUnderNavigationBar = true) {
        val p = shown[0] ?: return@DrafftSheet
        ProfileDetailView(
            profile = p,
            mode = ProfileDetailMode.DISCOVER,
            onDecision = { liked, opener ->
                onClose()
                app.swipe(p, liked = liked, opener = opener)
            },
            onSuperLike = { opener ->
                onClose()
                app.swipe(p, liked = true, superLike = true, opener = opener)
            },
        )
    }
}
