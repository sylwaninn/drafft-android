package so.drafft.app.feature.me

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import so.drafft.app.feature.discover.FiltersSheet
import so.drafft.app.feature.profile.ProfileDetailMode
import so.drafft.app.feature.profile.ProfileDetailView
import so.drafft.app.feature.verification.ChangePhoneSheet
import so.drafft.app.feature.verification.SupportSheet
import so.drafft.core.data.AppModel
import so.drafft.core.data.notifications.NotificationService
import so.drafft.core.data.platform.AppInfo
import so.drafft.core.data.platform.Haptics
import so.drafft.core.model.Brand
import so.drafft.core.model.DateText
import so.drafft.core.model.L
import so.drafft.core.ui.LocalAppModel
import so.drafft.core.ui.components.AdaptiveRow
import so.drafft.core.ui.components.ConfirmAction
import so.drafft.core.ui.components.DrafftButton
import so.drafft.core.ui.components.DrafftConfirm
import so.drafft.core.ui.components.DrafftSheet
import so.drafft.core.ui.components.GlassCircleButton
import so.drafft.core.ui.components.LocalSheetDismiss
import so.drafft.core.ui.components.LocalTabBarInset
import so.drafft.core.ui.components.NightBlock
import so.drafft.core.ui.components.Photo
import so.drafft.core.ui.components.PressScaleButton
import so.drafft.core.ui.components.SheetDetent
import so.drafft.core.ui.components.SparkPlus
import so.drafft.core.ui.components.SportBadgeStack
import so.drafft.core.ui.components.TopBar
import so.drafft.core.ui.components.draftTrail
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.LocalReduceMotion
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.bold
import so.drafft.core.ui.theme.branded
import so.drafft.core.ui.theme.display
import so.drafft.core.ui.theme.semibold

// Port of Drafft/Features/Me/MeView.swift.

/** The sheets You opens (`MeView.MeSheet` on iOS). */
enum class MeSheet(val rawValue: String) {
    EDIT("edit"), PREVIEW("preview"), FILTERS("filters"), EMAIL("email"), PHONE("phone"), PASSWORD("password"),
    EXPORT("export"), DELETE("delete"), PAYWALL("paywall"), NOTIFICATIONS("notifications"), LANGUAGE("language"),
    BLOCKED("blocked"), SAFETY("safety"), HELP("help"), LEGAL("legal"), SUBSCRIPTION("subscription");

    val id: String get() = rawValue
}

/** Marker for rows that use the drafft tempo spark instead of an SF Symbol (`MeView.sparkIcon`). */
const val MeViewSparkIcon = "drafft.spark"

/** The "You" tab: your card at the top, then settings grouped by what people come here to do. */
@Composable
fun MeView(modifier: Modifier = Modifier) {
    val app = LocalAppModel.current
    val notifications = koinInject<NotificationService>()
    val appInfo = koinInject<AppInfo>()
    var sheet by rememberSaveable { mutableStateOf<MeSheet?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val p = DS.palette
    val buildLabel = remember(appInfo) { buildLabel(appInfo) }

    // No title on You: just the blur under the status bar.
    TopBar(
        scroll = scroll,
        bar = { Spacer(Modifier.height(DS.Space.xs)) },
        modifier = modifier.fillMaxSize().background(p.canvasSoft),
    ) { bars ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(bars)
                .padding(top = DS.Space.xs)
                .padding(start = DS.Space.lg, end = DS.Space.lg, bottom = DS.Space.xxl + LocalTabBarInset.current),
            verticalArrangement = Arrangement.spacedBy(DS.Space.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // While paused, a strip slides out from under the card: the card and what it says about
            // you, then that no one sees it for now.
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.zIndex(1f)) {
                    if (app.profileLoad == AppModel.ProfileLoad.LOADED) {
                        LoadedProfileCard(onEdit = { sheet = MeSheet.EDIT }, onPreview = { sheet = MeSheet.PREVIEW })
                    } else {
                        ProfileLoadCard()
                    }
                }
                PausedStrip(app.profilePaused)
            }
            if (!app.isPremium) PlusCard { sheet = MeSheet.PAYWALL }

            Group(L("Discovery")) {
                SettingsRow(L("Filters"), "slider.horizontal.3", filtersSummary(app)) { sheet = MeSheet.FILTERS }
                Separator()
                ToggleSettingsRow(
                    L("Pause my profile"),
                    "pause.fill",
                    // One text, on or off: the strip under the card says it's on.
                    detail = L("Hides you from Discover and likes. Your chats and sessions carry on."),
                    isOn = app.profilePaused,
                    onChange = { app.profilePaused = it },
                    tint = p.paused,
                )
            }
            Group(L("Preferences")) {
                SettingsRow(
                    L("Notifications"),
                    "bell.fill",
                    if (notifications.isAllowed) L("Matches, messages, sessions") else L("Off"),
                ) { sheet = MeSheet.NOTIFICATIONS }
                Separator()
                SettingsRow(L("Language"), "globe", app.language.displayName) { sheet = MeSheet.LANGUAGE }
            }
            Group(L("Account")) {
                SettingsRow(L("Email"), "envelope.fill", app.email) { sheet = MeSheet.EMAIL }
                Separator()
                SettingsRow(L("Phone"), "phone.fill", app.phoneNumber ?: L("Add your number")) { sheet = MeSheet.PHONE }
                Separator()
                SettingsRow(L("Password"), "key.fill", L("Change your password")) { sheet = MeSheet.PASSWORD }
                // Only while subscribed: without it, the tier card above is the way in.
                val sub = app.subscription
                if (app.isPremium && sub != null) {
                    Separator()
                    val ends = DateText.dayMonth(sub.periodEnds)
                    SettingsRow(
                        Brand.TIER_NAME,
                        MeViewSparkIcon,
                        if (sub.willRenew) L("Renews %s", ends) else L("Ends %s", ends),
                    ) { sheet = MeSheet.SUBSCRIPTION }
                }
            }
            Group(L("Privacy & data")) {
                SettingsRow(
                    L("Blocked people"),
                    "hand.raised.fill",
                    if (app.blockedCount == 0) L("No one") else "${app.blockedCount}",
                ) { sheet = MeSheet.BLOCKED }
                Separator()
                SettingsRow(
                    L("Export my data"),
                    "square.and.arrow.down.fill",
                    if (app.dataExportRequestedAt == null) L("Sent to you by email") else L("Requested, check your inbox"),
                ) { sheet = MeSheet.EXPORT }
                Separator()
                // drafft can't work without the gender: withdrawing the consent is deleting the account.
                SettingsRow(
                    L("Sensitive data consent"),
                    "checkmark.shield.fill",
                    L("Withdrawing it means deleting your account."),
                ) { sheet = MeSheet.DELETE }
            }
            Group(L("Help")) {
                SettingsRow(L("Safety tips"), "shield.lefthalf.filled", L("Meeting someone for the first time")) { sheet = MeSheet.SAFETY }
                Separator()
                SettingsRow(L("Help center"), "questionmark.circle.fill", null) { sheet = MeSheet.HELP }
                Separator()
                SettingsRow(L("Terms & privacy policy"), "doc.text.fill", null) { sheet = MeSheet.LEGAL }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(p.canvas, RoundedCornerShape(DS.Radius.xl))
                    .padding(horizontal = DS.Space.lg),
            ) {
                AccountAction(L("Log out"), p.ink) { confirmLogout = true }
                Separator()
                AccountAction(L("Delete account"), p.negative) { sheet = MeSheet.DELETE }
            }
            // Under the last block, the page's one quiet footnote (a user-requested exception to
            // "no loose text"): which app this is, down to the build.
            Text(branded(buildLabel), Modifier.padding(top = DS.Space.xs), style = TextStyles.caption, color = p.mute)
        }
    }

    DrafftConfirm(
        visible = confirmLogout,
        onDismissRequest = { confirmLogout = false },
        icon = "rectangle.portrait.and.arrow.right",
        title = L("Log out?"),
        message = L("Your matches and chats stay safe. Log back in to see them."),
        actions = listOf(ConfirmAction(L("Log out"), ConfirmAction.Kind.DESTRUCTIVE) { app.signOut() }),
    )

    sheet?.let { s ->
        DrafftSheet(
            onDismissRequest = { sheet = null },
            detent = if (s == MeSheet.LANGUAGE) SheetDetent.MEDIUM else SheetDetent.LARGE,
            showsGrabber = s != MeSheet.PREVIEW,
            drawsUnderNavigationBar = s == MeSheet.PREVIEW,
        ) {
            when (s) {
                MeSheet.EDIT -> EditProfileView(profile = app.me)
                MeSheet.PREVIEW -> Box(Modifier.fillMaxSize()) {
                    ProfileDetailView(profile = app.publicMe, mode = ProfileDetailMode.ME)
                    GlassCircleButton(
                        "xmark",
                        onClick = LocalSheetDismiss.current,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(horizontal = DS.Space.lg, vertical = DS.Space.xs),
                        contentDescription = L("Close"),
                    )
                }
                MeSheet.FILTERS -> FiltersSheet(filters = app.filters)
                MeSheet.EMAIL -> ChangeEmailSheet()
                MeSheet.PHONE -> ChangePhoneSheet()
                MeSheet.NOTIFICATIONS -> NotificationsSettingsView()
                MeSheet.LANGUAGE -> LanguageSheet()
                MeSheet.PASSWORD -> ChangePasswordSheet()
                MeSheet.EXPORT -> ExportDataSheet()
                MeSheet.DELETE -> DeleteAccountSheet()
                MeSheet.PAYWALL -> PaywallView(
                    headline = L("Train at your tempo."),
                    pitch = L("See who already likes you, send unlimited likes and get a free boost every week."),
                )
                MeSheet.SUBSCRIPTION -> SubscriptionSheet()
                MeSheet.BLOCKED -> BlockedPeopleSheet()
                MeSheet.SAFETY -> SafetyTipsSheet()
                MeSheet.HELP -> SupportSheet(topic = L("General question"))
                MeSheet.LEGAL -> LegalDocsListSheet()
            }
        }
    }
}

/** "drafft 1.0 (12)", with the environment in the name off production ("drafft β", "drafft local"). */
private fun buildLabel(info: AppInfo): String {
    val name = when (info.environment) {
        "" -> Brand.NAME
        "staging" -> "${Brand.NAME} β"
        else -> "${Brand.NAME} ${info.environment}"
    }
    return "$name ${info.version} (${info.build})"
}

private fun filtersSummary(app: AppModel): String {
    val f = app.filters
    val ages = "${f.ages.first}–${f.ages.last}"
    return "${f.distanceLabel}, $ages, ${f.audience.title}"
}

// MARK: Profile card

/**
 * Until the server's profile is in: loading, or why it isn't and a way to try again. Never a profile
 * that isn't theirs, and nothing to edit or preview yet.
 */
@Composable
private fun ProfileLoadCard() {
    val app = LocalAppModel.current
    val scope = rememberCoroutineScope()
    val failed = app.profileLoad == AppModel.ProfileLoad.FAILED
    NightBlock(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .padding(DS.Space.xl)
                .animateContentSize(Motion.snappy()),
            verticalArrangement = Arrangement.spacedBy(DS.Space.lg),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(DS.Space.lg), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(84.dp)
                        .background(Color.White.copy(alpha = 0.14f), CircleShape)
                        .clearAndSetSemantics { },
                    contentAlignment = Alignment.Center,
                ) {
                    if (failed) {
                        DrafftIcon("person.fill", size = 38.dp, tint = Color.White)
                    } else {
                        CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (failed) L("Your profile couldn't load") else L("Loading your profile"),
                        style = TextStyles.headline,
                        color = Color.White,
                    )
                    if (failed) {
                        Text(L("Check your connection and try again."), style = TextStyles.footnote, color = Color.White.copy(alpha = 0.8f))
                    }
                }
            }
            if (failed) {
                DrafftButton(onClick = { scope.launch { app.loadProfile() } }) {
                    DrafftIcon("arrow.clockwise", size = symbolSize(TextStyles.body), tint = LocalContentColor.current)
                    Text(L("Try again"), maxLines = 2)
                }
            }
        }
    }
}

@Composable
private fun LoadedProfileCard(onEdit: () -> Unit, onPreview: () -> Unit) {
    val app = LocalAppModel.current
    val p = DS.palette
    val completion = app.profileCompletion
    val me = app.publicMe
    val preview = L("Preview my profile")
    NightBlock(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .padding(DS.Space.xl)
                .animateContentSize(Motion.snappy()),
            verticalArrangement = Arrangement.spacedBy(DS.Space.lg),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(DS.Space.lg), verticalAlignment = Alignment.CenterVertically) {
                PressScaleButton(onClick = onPreview, contentDescription = preview) {
                    Photo(
                        me.portrait,
                        Modifier
                            .size(84.dp)
                            .clip(CircleShape)
                            .border(3.dp, p.accentOnNight, CircleShape),
                        side = 84.dp,
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(L("%s, %d", me.name, me.age), style = display(26f), color = Color.White)
                    // White discs, ink glyphs: a grey disc disappeared into the night card.
                    SportBadgeStack(
                        me.sports.map { it.sport },
                        Modifier.padding(top = 2.dp),
                        fill = Color.White,
                        glyph = p.night,
                    )
                }
            }

            if (completion.value < 1) {
                val percent = (completion.value * 100).toInt()
                Column(verticalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
                    // The next step shares the line while it fits in full, under it otherwise.
                    AdaptiveRow(
                        leading = {
                            Text(L("Profile %d%% complete", percent), style = TextStyles.subheadline.semibold, color = Color.White)
                        },
                        trailing = {
                            completion.next?.let { Text(it, style = TextStyles.footnote, color = p.accentOnNight) }
                        },
                    )
                    val label = L("Profile %d percent complete", percent)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clearAndSetSemantics { contentDescription = label }
                            .background(Color.White.copy(alpha = 0.14f), CircleShape),
                    ) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(completion.value.toFloat())
                                .background(p.accentOnNight, CircleShape),
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(DS.Space.sm), verticalAlignment = Alignment.CenterVertically) {
                DrafftButton(
                    onClick = onEdit,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp)
                        .draftTrail(RoundedCornerShape(DS.Radius.xl), step = DpOffset((-6).dp, 0.dp)),
                ) {
                    DrafftIcon("pencil", size = symbolSize(TextStyles.body), tint = LocalContentColor.current)
                    Text(L("Edit profile"), maxLines = 2)
                }
                PressScaleButton(
                    onClick = onPreview,
                    modifier = Modifier
                        .size(52.dp)
                        .background(Color.White.copy(alpha = 0.14f), CircleShape),
                    contentDescription = preview,
                ) {
                    DrafftIcon("eye.fill", size = symbolSize(TextStyles.body), tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun PlusCard(onClick: () -> Unit) {
    val p = DS.palette
    PressScaleButton(onClick = onClick, modifier = Modifier.fillMaxWidth(), scale = 0.98f) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(p.lime, RoundedCornerShape(DS.Radius.xl))
                .padding(DS.Space.lg),
            horizontalArrangement = Arrangement.spacedBy(DS.Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // On an accent surface everything takes the on-accent colour: the glyph too, on a wash
            // of it (never a night disc with an accent glyph dropped in).
            Box(
                Modifier
                    .size(44.dp)
                    .background(p.onLimeWash, CircleShape)
                    .clearAndSetSemantics { },
                contentAlignment = Alignment.Center,
            ) {
                Spark(p.onLime, Modifier.size(22.dp, 16.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    branded(L("Get drafft tempo"), brandWeight = FontWeight.ExtraBold, tierColor = p.tierOnAccent),
                    style = TextStyles.headline,
                    color = p.onLime,
                )
                // White on the accent only in semibold or bolder, at full strength.
                Text(L("Undo your last swipe, see who likes you, unlimited likes."), style = TextStyles.footnote.semibold, color = p.onLime)
            }
            DrafftIcon("chevron.right", size = symbolSize(TextStyles.footnote), tint = p.onLime)
        }
    }
}

/** The drafft tempo spark, filled. */
@Composable
private fun Spark(color: Color, modifier: Modifier) {
    val shape = remember { SparkPlus() }
    Box(
        modifier.drawBehind {
            val outline: Outline = shape.createOutline(size, layoutDirection, this)
            drawOutline(outline, color)
        },
    )
}

// MARK: Paused strip

/**
 * While the profile is paused, the strip under the You card: the same colour as the switch that
 * paused it, and what it means in one line. The switch in Discovery is the way back.
 *
 * It sits under the card's bottom by the card's radius, drawn behind it (the card is above in z), so
 * it reads as another card continuing beneath.
 */
@Composable
private fun PausedStrip(visible: Boolean) {
    val p = DS.palette
    val reduceMotion = LocalReduceMotion.current
    // The strip comes and goes with the pause (the switch or the server): it slides out from under the
    // card while its height opens, both on one curve, so its bottom edge and the blocks under it move
    // together. No bounce: a spring overshooting read as a jolt.
    val sizeSpec = if (reduceMotion) tween<IntSize>(200, easing = Motion.EaseInOut) else Motion.springOf(0.35, 1f, IntSize.VisibilityThreshold)
    val offsetSpec = Motion.springOf(0.35, 1f, IntOffset.VisibilityThreshold)
    AnimatedVisibility(
        visible,
        Modifier.fillMaxWidth(),
        enter = if (reduceMotion) {
            fadeIn(tween(200, easing = Motion.EaseInOut)) + expandVertically(sizeSpec, Alignment.Top, clip = false)
        } else {
            expandVertically(sizeSpec, Alignment.Top, clip = false) + slideInVertically(offsetSpec) { -it }
        },
        exit = if (reduceMotion) {
            fadeOut(tween(200, easing = Motion.EaseInOut)) + shrinkVertically(sizeSpec, Alignment.Top, clip = false)
        } else {
            shrinkVertically(sizeSpec, Alignment.Top, clip = false) + slideOutVertically(offsetSpec) { -it }
        },
    ) {
        val line = buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(L("Profile paused")) }
            append(" ")
            append(L("No one sees you in Discover."))
        }
        Row(
            Modifier
                .fillMaxWidth()
                // Tucked under the card by its radius: laid out that much shorter, drawn that much higher.
                .layout { measurable, constraints ->
                    val tuck = DS.Radius.xl.roundToPx()
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, (placeable.height - tuck).coerceAtLeast(0)) { placeable.place(0, -tuck) }
                }
                // Square at the top: it continues the card rather than sitting behind it.
                .background(p.paused, RoundedCornerShape(bottomStart = DS.Radius.xl, bottomEnd = DS.Radius.xl))
                .padding(start = DS.Space.xl, end = DS.Space.xl, top = DS.Radius.xl + DS.Space.md, bottom = DS.Space.md)
                .semantics(mergeDescendants = true) { },
            horizontalArrangement = Arrangement.spacedBy(DS.Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DrafftIcon("pause.fill", Modifier.clearAndSetSemantics { }, size = symbolSize(TextStyles.footnote), tint = p.onPaused)
            Text(line, Modifier.weight(1f), style = TextStyles.subheadline, color = p.onPaused)
        }
    }
}

// MARK: Rows

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    val p = DS.palette
    Column(
        Modifier
            .fillMaxWidth()
            .background(p.canvas, RoundedCornerShape(DS.Radius.xl))
            .padding(start = DS.Space.lg, end = DS.Space.lg, bottom = DS.Space.xs),
    ) {
        Text(
            title,
            Modifier
                .padding(top = DS.Space.lg, bottom = DS.Space.xs)
                .semantics { heading() },
            style = TextStyles.footnote.bold,
            color = p.mute,
        )
        content()
    }
}

@Composable
private fun Separator() = Hairline(start = 52.dp)

@Composable
private fun RowIcon(name: String) {
    val p = DS.palette
    Box(
        Modifier
            .size(36.dp)
            .background(p.canvasSoft, CircleShape)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        if (name == MeViewSparkIcon) {
            Spark(p.ink, Modifier.size(18.dp, 13.dp))
        } else {
            DrafftIcon(name, size = 17.dp, tint = p.ink)
        }
    }
}

@Composable
private fun SettingsRow(title: String, icon: String, value: String?, action: () -> Unit) {
    val p = DS.palette
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) {
                Haptics.tap()
                action()
            }
            .padding(vertical = DS.Space.md),
        horizontalArrangement = Arrangement.spacedBy(DS.Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                branded(title, brandWeight = FontWeight.ExtraBold, tierColor = p.accentInk),
                style = TextStyles.body.semibold,
                color = p.ink,
            )
            if (value != null) Text(value, style = TextStyles.footnote, color = p.body, maxLines = 2)
        }
        DrafftIcon("chevron.right", size = symbolSize(TextStyles.footnote), tint = p.mute)
    }
}

/** Same layout as a settings row, but nothing to open: not a button, no chevron. */
@Composable
private fun InfoRow(title: String, icon: String, value: String) {
    val p = DS.palette
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = DS.Space.md)
            .semantics(mergeDescendants = true) { },
        horizontalArrangement = Arrangement.spacedBy(DS.Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = TextStyles.body.semibold, color = p.ink)
            Text(value, style = TextStyles.footnote, color = p.body)
        }
    }
}

@Composable
private fun ToggleSettingsRow(
    title: String,
    icon: String,
    detail: String?,
    isOn: Boolean,
    onChange: (Boolean) -> Unit,
    tint: Color = DS.palette.lime,
) {
    val p = DS.palette
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = DS.Space.md),
        horizontalArrangement = Arrangement.spacedBy(DS.Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = TextStyles.body.semibold, color = p.ink)
            if (detail != null) {
                Text(detail, style = TextStyles.footnote, color = p.body)
            }
        }
        DrafftSwitch(
            checked = isOn,
            onCheckedChange = {
                onChange(it)
                Haptics.select()
            },
            tint = tint,
        )
    }
}

@Composable
private fun AccountAction(title: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, style = TextStyles.body.semibold, color = color)
    }
}
