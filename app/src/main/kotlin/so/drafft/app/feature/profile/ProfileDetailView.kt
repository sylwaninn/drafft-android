package so.drafft.app.feature.profile

// Ports Drafft/Features/Profile/ProfileDetailView.swift.

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import so.drafft.app.feature.discover.ExtrasSheet
import so.drafft.app.feature.discover.NameAgeLine
import so.drafft.app.feature.discover.SuperLikeComposer
import so.drafft.app.feature.matches.UnmatchButton
import so.drafft.core.data.audio.AudioPlayback
import so.drafft.core.data.location.LocationPrivacy
import so.drafft.core.data.platform.Haptics
import so.drafft.core.data.telemetry.AnalyticsEvent
import so.drafft.core.data.telemetry.Screen
import so.drafft.core.data.telemetry.Telemetry
import so.drafft.core.model.L
import so.drafft.core.model.MessageContent
import so.drafft.core.model.Profile
import so.drafft.core.model.ProfilePrompt
import so.drafft.core.ui.LocalAppModel
import so.drafft.core.ui.TrackScreen
import so.drafft.core.ui.components.DrafftSheet
import so.drafft.core.ui.components.InteractiveDismissDisabled
import so.drafft.core.ui.components.GlassCircleButton
import so.drafft.core.ui.components.LocalSheetDismiss
import so.drafft.core.ui.components.Photo
import so.drafft.core.ui.components.SuperLikeCountMark
import so.drafft.core.ui.components.SuperLikeMark
import so.drafft.core.ui.components.glass
import so.drafft.core.ui.components.pressScale
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.displayBold
import so.drafft.core.ui.theme.heavy
import so.drafft.core.ui.theme.medium
import so.drafft.core.ui.theme.semibold

/** Where a profile detail is shown (Swift `ProfileDetailView.Mode`). */
enum class ProfileDetailMode { DISCOVER, SHEET, ME }

/**
 * A profile in full, presented in a sheet (`DrafftSheet` with no grabber, drawn under the navigation
 * bar, so the photo meets the sheet's top edge and the profile runs to the bottom). Discover: like, pass or super like it,
 * or like one photo or prompt with a note. Close (top right) closes the sheet it sits in; in `ME`
 * mode the presenter puts its own.
 */
@Composable
fun ProfileDetailView(
    profile: Profile,
    mode: ProfileDetailMode = ProfileDetailMode.SHEET,
    /** Discover only: (liked, icebreaker opener to attach to the like). */
    onDecision: ((Boolean, MessageContent?) -> Unit)? = null,
    /**
     * Discover only: a confirmed super like, with its optional note. Confirmation happens here,
     * over the profile, so cancelling brings you straight back to it.
     */
    onSuperLike: ((MessageContent?) -> Unit)? = null,
    /**
     * Outside Discover (a matched profile opened from a chat): offered Report or block, and
     * told once it's done so the chat can close behind it.
     */
    onBlocked: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    TrackScreen(Screen.PROFILE_DETAIL)
    LaunchedEffect(profile.id) {
        Telemetry.track(AnalyticsEvent.ProfileViewed(mode.name.lowercase(), hasVoice = profile.voiceIntro != null, photos = profile.allPhotos.size))
    }
    val app = LocalAppModel.current
    val audio = koinInject<AudioPlayback>()
    val dismiss = LocalSheetDismiss.current
    val p = DS.palette
    val pager = rememberPagerState { profile.allPhotos.size }
    var showSafety by remember { mutableStateOf(false) }
    var pendingLike by remember { mutableStateOf<LikeTarget?>(null) }
    var superLiking by remember { mutableStateOf(false) }
    var showExtras by remember { mutableStateOf(false) }
    val discover = mode == ProfileDetailMode.DISCOVER
    val scroll = rememberScrollState()
    var barHeight by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current

    /** Written prompts that have an answer: a skipped prompt never shows as an empty card. */
    val prompts = remember(profile.prompts) { profile.prompts.filter { it.answer.isNotBlank() } }

    /**
     * Discover: the card leaves like a pass, then the block lands (undo can't restore it).
     * Elsewhere: the profile closes and the caller closes the chat; the block lands after.
     */
    fun blockAndLeave() {
        Haptics.success()
        val person = profile
        if (discover) {
            onDecision?.invoke(false, null)
        } else {
            dismiss()
            onBlocked?.invoke()
        }
        // The model's own scope: this view leaves right away.
        app.scope.launch {
            delay(350)
            app.block(person)
        }
    }

    fun likePhoto(name: String) {
        pendingLike = LikeTarget.Photo(name)
    }

    DisposableEffect(Unit) { onDispose { audio.stop() } }
    // A composer open over the profile: the sheet stays put, and system back reaches the composer's Cancel.
    InteractiveDismissDisabled(pendingLike != null || superLiking)

    // Behind a composer the profile blurs, standing in for the iPhone's material veil.
    val veil by animateDpAsState(if (pendingLike != null || superLiking) 18.dp else 0.dp, tween(180, easing = Motion.EaseOut), label = "veil")

    Box(modifier.fillMaxSize().background(p.canvasSoft)) {
        Column(
            Modifier
                .fillMaxSize()
                .blur(veil)
                .verticalScroll(scroll)
                // The sheet draws under the navigation bar: the bar (Discover) or the scroll's end
                // clears it.
                .then(if (discover) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)))
                .padding(bottom = DS.Space.xxl + if (discover) barHeight else 0.dp),
            verticalArrangement = Arrangement.spacedBy(DS.Space.xl),
        ) {
            Gallery(profile, pager, discover) { likePhoto(it) }
            Header(profile, mode, Modifier.padding(horizontal = DS.Space.lg))

            Column(
                Modifier.padding(horizontal = DS.Space.lg),
                verticalArrangement = Arrangement.spacedBy(DS.Space.xxl),
            ) {
                if (profile.superLikedMe && discover) ReceivedSuperLike(profile)

                VitalsStrip(
                    profile,
                    showDistance = false,
                    showsPlace = false,
                    // Sits closer to what follows (the iPhone's `.padding(.bottom, -md)`).
                    modifier = Modifier.layout { m, c ->
                        val placeable = m.measure(c)
                        val trim = DS.Space.md.roundToPx()
                        layout(placeable.width, (placeable.height - trim).coerceAtLeast(0)) { placeable.place(0, 0) }
                    },
                )

                if (profile.voiceIntro != null) VoiceBlock(profile)

                val promptCard: @Composable (ProfilePrompt) -> Unit = { prompt ->
                    PromptCard(prompt, onLike = if (discover) ({ pendingLike = LikeTarget.Prompt(prompt) }) else null)
                }
                val photo: @Composable (String) -> Unit = { name ->
                    LikablePhoto(name, onLike = if (discover) ({ likePhoto(name) }) else null)
                }

                prompts.getOrNull(0)?.let { promptCard(it) }

                SportsWeekBlock(profile, me = if (mode == ProfileDetailMode.ME) null else app.me)

                profile.photos.getOrNull(0)?.let { photo(it) }

                prompts.getOrNull(1)?.let { promptCard(it) }

                if (profile.icebreaker.isComplete) {
                    IcebreakerCard(
                        profile,
                        onSend = if (discover) {
                            { opener ->
                                app.scope.launch {
                                    delay(150)
                                    onDecision?.invoke(true, opener)
                                }
                            }
                        } else null,
                        sendTitle = L("Like with this answer"),
                    )
                }

                profile.photos.getOrNull(1)?.let { photo(it) }

                prompts.getOrNull(2)?.let { promptCard(it) }

                if (profile.goal.isNotBlank()) GoalBlock(profile.goal)

                profile.photos.getOrNull(2)?.let { photo(it) }

                if (discover || onBlocked != null) {
                    // Deliberately quiet: available, never inviting.
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .defaultMinSize(minHeight = 44.dp)
                                .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) { showSafety = true }
                                .clearAndSetSemantics { contentDescription = L("Report or block %s", profile.name) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                Modifier
                                    .defaultMinSize(minHeight = 36.dp)
                                    .border(1.dp, p.hairline, CircleShape)
                                    .padding(horizontal = DS.Space.lg),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                DrafftIcon("shield-warning", size = symbol(13f), tint = p.body)
                                Text(L("Report or block"), style = TextStyles.footnote.medium, color = p.body)
                            }
                        }
                    }
                    if (onBlocked != null && app.matches.any { it.profile.id == profile.id }) {
                        UnmatchButton(profile = profile, onDone = {
                            dismiss()
                            onBlocked()
                        })
                    }
                }
            }
        }

        // No blur here (ours or the system's): the like/pass buttons float over the profile.
        if (discover) {
            DecisionBar(
                profile,
                superLikes = app.superLikes,
                hasSuperLike = onSuperLike != null,
                onPass = { onDecision?.invoke(false, null) },
                onLike = { onDecision?.invoke(true, null) },
                onSuperLike = {
                    if (app.superLikes == 0) {
                        Haptics.warning()
                        showExtras = true
                    } else {
                        Haptics.tap()
                        superLiking = true
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                    .onSizeChanged { barHeight = with(density) { it.height.toDp() } },
            )
        }

        if (mode != ProfileDetailMode.ME && pendingLike == null && !superLiking) {
            GlassCircleButton(
                "close",
                dismiss,
                Modifier.align(Alignment.TopEnd).padding(DS.Space.lg),
                contentDescription = L("Close"),
            )
        }

        if (superLiking) {
            SuperLikeComposer(
                profile = profile,
                left = app.superLikes,
                onSend = { note ->
                    superLiking = false
                    app.scope.launch {
                        delay(120)
                        onSuperLike?.invoke(if (note.isEmpty()) null else MessageContent.Text(note))
                    }
                },
                onCancel = { superLiking = false },
            )
        }

        pendingLike?.let { target ->
            LikeComposer(
                target = target,
                name = profile.name,
                onSend = { message ->
                    val content: MessageContent = when (target) {
                        is LikeTarget.Photo -> MessageContent.PhotoReply(asset = target.name, reply = message)
                        is LikeTarget.Prompt -> MessageContent.IcebreakerReply(quote = target.prompt.answer, reply = message)
                    }
                    pendingLike = null
                    app.scope.launch {
                        delay(120)
                        onDecision?.invoke(true, content)
                    }
                },
                onCancel = { pendingLike = null },
            )
        }
    }

    DrafftSheet(visible = showSafety, onDismissRequest = { showSafety = false }) {
        ReportSheet(profile, onDone = { blockAndLeave() })
    }
    DrafftSheet(visible = showExtras, onDismissRequest = { showExtras = false }) {
        ExtrasSheet(tab = ExtrasSheet.Tab.SUPER_LIKE)
    }
}

// MARK: Pieces

@Composable
private fun Gallery(profile: Profile, pager: androidx.compose.foundation.pager.PagerState, discover: Boolean, onLikePhoto: (String) -> Unit) {
    val photos = profile.allPhotos
    Box(
        Modifier
            .fillMaxWidth()
            .height(440.dp)
            .clip(RoundedCornerShape(bottomStart = DS.Radius.xl, bottomEnd = DS.Radius.xl))
            .semantics { contentDescription = L("Photos of %s, %d total", profile.name, photos.size) },
    ) {
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { it }) { i ->
            Photo(photos[i], Modifier.fillMaxSize())
        }
        // Page steps centered on the photo, like a system page control; the heart keeps the corner.
        // Both share one bottom line so they read as one row, not two stray pieces.
        if (photos.size > 1) {
            // Indicator only: swipes on it still page the photos.
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = DS.Space.lg)
                    .height(52.dp),
                contentAlignment = Alignment.Center,
            ) {
                PhotoSteps(photos.size, pager.currentPage)
            }
        }
        if (discover) {
            LikeHeartButton(
                label = L("Like this photo"),
                action = { onLikePhoto(photos[pager.currentPage.coerceIn(0, photos.lastIndex)]) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(DS.Space.lg),
            )
        }
    }
}

@Composable
private fun Header(profile: Profile, mode: ProfileDetailMode, modifier: Modifier) {
    val p = DS.palette
    // Straight on the page, not in a block (user-requested exception to "no loose text"):
    // the person's intro reads as the page's own title, lined up with the blocks' edges.
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
        NameAgeLine(
            profile = profile,
            nameSize = 32f,
            nameColor = p.ink,
            ageColor = p.body,
            // No heart by the name: the super like block right below says it.
            showsBadge = false,
            modifier = Modifier.semantics { heading() },
        )
        // Where they are, right under the name (not buried in the facts below).
        // No pin icon (DESIGN.md: the place name stands on its own). Your own profile shows
        // the area only: a distance to yourself means nothing.
        if (mode != ProfileDetailMode.ME) {
            val distance = LocationPrivacy.rounded(profile.distanceKm)
            Text(
                if (profile.neighborhood.isEmpty()) L("%s away", distance) else L("%s, %s away", profile.neighborhood, distance),
                style = TextStyles.subheadline.semibold,
                color = p.ink,
            )
        } else if (profile.neighborhood.isNotEmpty()) {
            Text(profile.neighborhood, style = TextStyles.subheadline.semibold, color = p.ink)
        }
        if (profile.bio.isNotBlank()) {
            Text(profile.bio, style = TextStyles.body, color = p.body)
        }
    }
}

/** Their super like, in full (the deck card only shows the first lines of the note). */
@Composable
private fun ReceivedSuperLike(profile: Profile) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(DS.palette.negative, RoundedCornerShape(DS.Radius.xl))
            .padding(DS.Space.xl)
            .semantics(mergeDescendants = true) { },
        verticalArrangement = Arrangement.spacedBy(DS.Space.md),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(DS.Space.sm), verticalAlignment = Alignment.CenterVertically) {
            SuperLikeMark(size = 16.dp, color = Color.White)
            Text(L("%s super liked you", profile.name), style = TextStyles.subheadline.heavy, color = Color.White)
        }
        val note = profile.superLikeNote
        if (!note.isNullOrEmpty()) {
            Text(note, style = displayBold(24f), color = Color.White)
        }
    }
}

@Composable
private fun DecisionBar(
    profile: Profile,
    superLikes: Int,
    hasSuperLike: Boolean,
    onPass: () -> Unit,
    onLike: () -> Unit,
    onSuperLike: () -> Unit,
    modifier: Modifier,
) {
    val p = DS.palette
    val shadow = Color.Black.copy(alpha = 0.22f)
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = DS.Space.md, bottom = DS.Space.sm),
        horizontalArrangement = Arrangement.spacedBy(DS.Space.xl, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Mirrors the super like button so cross and heart stay centered, as on the deck.
        if (hasSuperLike) Spacer(Modifier.width(56.dp))

        Box(
            Modifier
                .size(68.dp)
                // The whole disc passes, not just the glyph (glass isn't hit-testable).
                .pressScale(onPass, scale = 0.88f)
                .semantics { contentDescription = L("Pass on %s", profile.name) }
                .shadow(2.dp, CircleShape, ambientColor = shadow, spotColor = shadow)
                .glass(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            DrafftIcon("close", size = symbol(26f), tint = p.ink)
        }

        Box(
            Modifier
                .size(68.dp)
                .pressScale(onLike, scale = 0.88f)
                .semantics { contentDescription = L("Like %s", profile.name) }
                .shadow(2.dp, CircleShape, ambientColor = shadow, spotColor = shadow)
                .background(p.like, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            DrafftIcon("heart", size = symbol(28f), tint = p.onLike)
        }

        if (hasSuperLike) {
            Box(
                Modifier
                    .pressScale(onSuperLike, scale = 0.88f)
                    .semantics { contentDescription = L("Super like %s, %d left", profile.name, superLikes) }
                    .shadow(2.dp, CircleShape, ambientColor = shadow, spotColor = shadow),
            ) {
                SuperLikeCountMark(superLikes, size = 56.dp)
            }
        }
    }
}

/**
 * Where you are in the photos: dots, the current one stretched into a bar. Sits on dark glass
 * so it reads on any photo, bright or dark, without a halo shadow.
 */
@Composable
private fun PhotoSteps(count: Int, current: Int) {
    Row(
        Modifier
            .clearAndSetSemantics { }
            .glass(CircleShape, tint = Color.Black.copy(alpha = 0.3f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until count) {
            val on = i == current
            val width by animateDpAsState(if (on) 18.dp else 6.dp, Motion.snappy(), label = "photoStep")
            Box(
                Modifier
                    .size(width = width, height = 6.dp)
                    .background(Color.White.copy(alpha = if (on) 1f else 0.45f), CircleShape),
            )
        }
    }
}
