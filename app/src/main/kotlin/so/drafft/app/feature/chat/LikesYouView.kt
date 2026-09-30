package so.drafft.app.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import so.drafft.app.feature.discover.ProfileIdentity
import so.drafft.app.feature.profile.ProfileDetailMode
import so.drafft.app.feature.profile.ProfileDetailView
import so.drafft.core.model.L
import so.drafft.core.model.Profile
import so.drafft.core.ui.LocalAppModel
import so.drafft.core.ui.components.BlurredNavigationEdge
import so.drafft.core.ui.components.DrafftSheet
import so.drafft.core.ui.components.NightBlock
import so.drafft.core.ui.components.Photo
import so.drafft.core.ui.components.PressScaleButton
import so.drafft.core.ui.components.SheetNavBar
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.display

// Port of Drafft/Features/Chat/LikesYouView.swift.

/**
 * drafft tempo: everyone who already liked you. Like back and it's a match right away. Shown in a
 * sheet (`DrafftSheet`); Close is its own (`LocalSheetDismiss`).
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
            NightBlock(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(DS.Space.xl), verticalArrangement = Arrangement.spacedBy(DS.Space.xs)) {
                    Text(L("They like you."), Modifier.semantics { heading() }, style = display(34f), color = DS.palette.accentOnNight)
                    Text(L("Like back and it's a match straight away."), style = TextStyles.subheadline, color = Color.White.copy(alpha = 0.72f))
                }
            }

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
            }

            LikesGrid(app.likedMe) { p -> open = p }
        }
    }

    // Same presentation and actions as a profile opened from Discover.
    LikedProfileSheet(open) { open = null }
}

/** Two columns of like cards, [DS.Space.sm] apart. */
@Composable
internal fun LikesGrid(people: List<Profile>, onOpen: (Profile) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
        people.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
                row.forEach { p ->
                    PressScaleButton(
                        onClick = { onOpen(p) },
                        modifier = Modifier.weight(1f),
                        scale = 0.97f,
                        contentDescription = L("%s, %d. Open profile", p.name, p.age),
                    ) { LikeCard(p) }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** A person who likes you: their photo, a scrim under the name, name and age. */
@Composable
internal fun LikeCard(p: Profile) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(230.dp)
            .clip(RoundedCornerShape(DS.Radius.xl)),
    ) {
        Photo(p.portrait, Modifier.fillMaxSize(), side = 180.dp)
        // Photo scrim under the name.
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to DS.palette.night.copy(alpha = 0.8f))),
        )
        ProfileIdentity(
            profile = p,
            nameSize = 22f,
            showsLocation = false,
            showsSuperLike = true,
            modifier = Modifier.align(Alignment.BottomStart).padding(DS.Space.md),
        )
    }
}

/** A liked-you profile opened in a sheet: like or pass (or super like) as on Discover. */
@Composable
internal fun LikedProfileSheet(open: Profile?, onClose: () -> Unit) {
    val app = LocalAppModel.current
    // Kept while the sheet slides down.
    val shown = remember { arrayOfNulls<Profile>(1) }
    if (open != null) shown[0] = open
    DrafftSheet(visible = open != null, onDismissRequest = onClose) {
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
