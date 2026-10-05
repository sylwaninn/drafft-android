package so.drafft.app.feature.sessions

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.util.UUID
import org.koin.compose.koinInject
import so.drafft.app.feature.chat.CalendarButton
import so.drafft.app.feature.discover.FirstThatFits
import so.drafft.app.feature.me.SessionSafetySheet
import so.drafft.app.feature.profile.ProfileDetailMode
import so.drafft.app.feature.profile.ProfileDetailView
import so.drafft.app.feature.profile.ProposeSessionSheet
import so.drafft.core.data.platform.Haptics
import so.drafft.core.data.sessions.SessionRecord
import so.drafft.core.data.sessions.SessionStore
import so.drafft.core.data.sessions.from
import so.drafft.core.data.telemetry.Screen
import so.drafft.core.model.DateText
import so.drafft.core.model.L
import so.drafft.core.model.SessionProposal
import so.drafft.core.ui.LocalAppModel
import so.drafft.core.ui.TrackScreen
import so.drafft.core.ui.components.Avatar
import so.drafft.core.ui.components.CheckDisc
import so.drafft.core.ui.components.ConfirmAction
import so.drafft.core.ui.components.DrafftButton
import so.drafft.core.ui.components.DrafftButtonKind
import so.drafft.core.ui.components.DrafftConfirm
import so.drafft.core.ui.components.DrafftSheet
import so.drafft.core.ui.components.EdgeBars
import so.drafft.core.ui.components.GlassCircleButton
import so.drafft.core.ui.components.LocalSheetDismiss
import so.drafft.core.ui.components.PressScaleButton
import so.drafft.core.ui.components.SheetNavBar
import so.drafft.core.ui.components.TextLinkButton
import so.drafft.core.ui.navigation.LocalNavStack
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.bold
import so.drafft.core.ui.theme.display
import so.drafft.core.ui.theme.monospacedDigits
import so.drafft.core.ui.theme.semibold

/**
 * A session as a page of its own, reached from the Sessions tab, a person's list or the chat. The one
 * place where a session is answered, changed, called off, added to the calendar or made safe; the chat
 * only points here. It shows the session as the server has it (`SessionStore`), live.
 *
 * [onOpenChat]: "Open chat", for a page opened from the Sessions tab (it gets the chat's id). Null when
 * the person came from the chat: closing returns there, a second way would be noise.
 *
 * [presented]: shown in a sheet over the chat (a card, the banner), with its own Close at the top right;
 * otherwise a page pushed in the tab's stack, with Back.
 */
@Composable
fun SessionDetailView(
    sessionID: UUID,
    onOpenChat: ((String) -> Unit)? = null,
    presented: Boolean = false,
    modifier: Modifier = Modifier,
) {
    TrackScreen(Screen.SESSION)
    val app = LocalAppModel.current
    val store = koinInject<SessionStore>()
    val stack = LocalNavStack.current
    val record = store.record(sessionID)
    val session = record?.let { SessionProposal.from(it) }
    val chatID = record?.matchID?.toString()?.lowercase()
    val profile = chatID?.let { app.conversation(it)?.profile }
    val name = record?.partner?.name ?: profile?.name ?: ""
    val photo = record?.partner?.photo ?: profile?.portrait ?: ""
    val mine = record?.let(store::isMine) ?: false
    val busy = store.isBusy(sessionID)

    var selected by remember(sessionID) { mutableStateOf<Instant?>(null) }
    var confirmCancel by remember { mutableStateOf(false) }
    var counterTo by remember { mutableStateOf<SessionProposal?>(null) }
    var showSafety by remember { mutableStateOf(false) }
    // A new invite shows "Meet safely" before itself: the page stays hidden until that sheet is gone.
    var gated by remember { mutableStateOf(false) }
    var showProfile by remember { mutableStateOf(false) }

    /** An invite that arrived and waits on the person, read as it stands now. */
    fun waitsOnMe(): Boolean {
        val row = store.record(sessionID) ?: return false
        return row.status == SessionRecord.Status.PENDING && !store.isMine(row) && store.me != null
    }

    /** A new invite: "Meet safely" slides in first, once, before the person sees what is proposed. */
    fun checkSafetyGate() {
        if (!waitsOnMe() || store.hasShownSafety(sessionID) || showSafety) return
        gated = true
        showSafety = true
    }

    LaunchedEffect(sessionID) {
        store.need(sessionID)
        store.refresh()
        store.markSeen(sessionID)
        checkSafetyGate()
    }
    LaunchedEffect(session?.status, store.me) { checkSafetyGate() }
    // Looked at as it stands: no longer news, and whatever changes while it's on screen isn't either.
    LaunchedEffect(record?.updatedAt) { store.markSeen(sessionID) }
    DisposableEffect(sessionID) { onDispose { store.markSeen(sessionID) } }
    // A single time is picked for them.
    LaunchedEffect(session?.options) {
        val options = session?.options ?: return@LaunchedEffect
        if (options.size == 1 && selected == null) selected = options.first()
    }

    val scroll = rememberScrollState()
    val closeSheet = LocalSheetDismiss.current
    EdgeBars(
        scroll = scroll,
        modifier = modifier.fillMaxSize().background(DS.palette.canvasSoft),
        topBar = {
            if (presented) {
                // Close sits on the right, like every other sheet in the app.
                SheetNavBar(title = L("Session"), onClose = closeSheet)
            } else {
                SheetNavBar(
                    title = L("Session"),
                    onClose = null,
                    leading = { GlassCircleButton("alt-arrow-left", onClick = { stack.pop() }, contentDescription = L("Back")) },
                )
            }
        },
        bottomBar = {
            if (!gated && session != null && chatID != null) {
                Footer(
                    session = session,
                    mine = mine,
                    busy = busy,
                    selected = selected,
                    name = name,
                    chatID = chatID,
                    onConfirm = { pick -> app.respondToSession(session.id, accept = true, pick = pick) },
                    onOpenChat = onOpenChat?.let { open -> { open(chatID) } },
                )
            }
        },
        navigationEdge = true,
        // In a sheet, the sheet already keeps clear of the system bars.
        windowInsets = if (presented) WindowInsets(0, 0, 0, 0) else WindowInsets.safeDrawing,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .alpha(if (gated) 0f else 1f)
                .verticalScroll(scroll)
                .padding(padding)
                .padding(horizontal = DS.Space.lg)
                .padding(top = DS.Space.md, bottom = DS.Space.xl),
        ) {
            if (session != null) {
                Page(
                    s = session,
                    mine = mine,
                    busy = busy,
                    name = name,
                    photo = photo,
                    canAnswer = profile != null,
                    selected = selected,
                    onSelect = { selected = it },
                    onProfile = { if (profile != null) showProfile = true },
                    onCounter = { counterTo = session },
                    onDecline = { app.respondToSession(session.id, accept = false) },
                    onCancel = { confirmCancel = true },
                    onSafety = { showSafety = true },
                    onOpenChat = onOpenChat?.let { open -> chatID?.let { id -> { open(id) } } },
                )
            }
        }
    }

    DrafftConfirm(
        visible = confirmCancel,
        onDismissRequest = { confirmCancel = false },
        icon = "calendar-minus",
        title = L("Cancel this session?"),
        message = L("%s will be told it's off.", name),
        cancelTitle = L("Keep it"),
        actions = listOf(ConfirmAction(L("Cancel session"), ConfirmAction.Kind.DESTRUCTIVE) { app.cancelSession(sessionID) }),
    )
    val original = counterTo
    DrafftSheet(visible = original != null, onDismissRequest = { counterTo = null }) {
        val chat = chatID ?: return@DrafftSheet
        if (original == null || profile == null) return@DrafftSheet
        ProposeSessionSheet(
            profile = profile,
            me = app.publicMe,
            sendTitle = L("Send new times"),
            counterTo = original,
            onSend = { p -> app.counterSession(original.id, chat, p) },
        )
    }
    DrafftSheet(
        visible = showSafety,
        onDismissRequest = {
            showSafety = false
            store.markSafetyShown(sessionID)
            gated = false
        },
    ) { SessionSafetySheet() }
    DrafftSheet(
        visible = showProfile && profile != null,
        onDismissRequest = { showProfile = false },
        showsGrabber = false,
        drawsUnderNavigationBar = true,
    ) {
        // Blocking from it closes everything that was about them.
        if (profile != null) ProfileDetailView(profile = profile, mode = ProfileDetailMode.SHEET, onBlocked = { stack.popToRoot() })
    }
}

// MARK: Page

/**
 * Content straight on the page, not in a card: the sport and with whom, the title, what they wrote, then
 * one block for what the person can do.
 */
@Composable
private fun Page(
    s: SessionProposal,
    mine: Boolean,
    busy: Boolean,
    name: String,
    photo: String,
    /** The match is still there (its chat is known): the person can answer with other times. */
    canAnswer: Boolean,
    selected: Instant?,
    onSelect: (Instant) -> Unit,
    onProfile: () -> Unit,
    onCounter: () -> Unit,
    onDecline: () -> Unit,
    onCancel: () -> Unit,
    onSafety: () -> Unit,
    onOpenChat: (() -> Unit)?,
) {
    val p = DS.palette
    val chosen = s.chosen
    Column {
        Header(s, mine, name, photo, profileEnabled = canAnswer, onProfile = onProfile)

        if (s.status == SessionProposal.Status.ACCEPTED && chosen != null) {
            ConfirmedDate(chosen, Modifier.padding(top = DS.Space.xxl))
            if (s.title.isNotEmpty()) {
                Text(s.title, Modifier.padding(top = DS.Space.xl), style = display(22f), color = p.body)
            }
        } else if (s.title.isNotEmpty()) {
            Text(s.title, Modifier.padding(top = DS.Space.xl), style = display(32f), color = p.ink)
        }

        if (s.note.isNotEmpty()) {
            Text(s.note, Modifier.padding(top = DS.Space.lg), style = TextStyles.title3, color = p.body)
        }

        Block(
            s = s,
            mine = mine,
            busy = busy,
            name = name,
            canAnswer = canAnswer,
            selected = selected,
            onSelect = onSelect,
            onCounter = onCounter,
            onDecline = onDecline,
            onSafety = onSafety,
            onOpenChat = onOpenChat,
            modifier = Modifier.padding(top = DS.Space.xl),
        )

        if (s.status == SessionProposal.Status.PENDING || s.status == SessionProposal.Status.ACCEPTED) {
            QuietLink(L("Cancel session"), enabled = !busy, onClick = onCancel, modifier = Modifier.padding(top = DS.Space.sm))
        }
    }
}

/**
 * The sport leads, as the mark and the name; the person is a line under it ("with", their photo, their
 * name) and opens their profile. The state sits at the top right.
 */
@Composable
private fun Header(
    s: SessionProposal,
    mine: Boolean,
    name: String,
    photo: String,
    profileEnabled: Boolean,
    onProfile: () -> Unit,
) {
    val p = DS.palette
    val viewProfile = L("View %s's profile", name)
    Row(horizontalArrangement = Arrangement.spacedBy(DS.Space.md), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .size(64.dp)
                .background(p.lime, CircleShape)
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            DrafftIcon(s.sport.symbol, size = 32.dp, tint = p.onLime)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(s.sport.displayName, Modifier.semantics { heading() }, style = display(22f), color = p.ink)
            Row(
                Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .clickable(
                        remember { MutableInteractionSource() },
                        indication = null,
                        enabled = profileEnabled,
                        role = Role.Button,
                        onClickLabel = viewProfile,
                        onClick = onProfile,
                    ),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(L("with"), style = TextStyles.subheadline, color = p.body)
                Avatar(photo, size = 24.dp)
                Text(name, Modifier.weight(1f, fill = false), style = TextStyles.subheadline.semibold, color = p.ink)
            }
        }
        StatusChip(s, mine, Modifier.padding(top = 4.dp))
    }
}

/**
 * Where the session stands: confirmed is the sports' green everywhere, an answer to give is the accent,
 * the rest sits soft.
 */
@Composable
private fun StatusChip(s: SessionProposal, mine: Boolean, modifier: Modifier = Modifier) {
    val p = DS.palette
    val waitingOnMe = s.status == SessionProposal.Status.PENDING && !mine
    val text = when (s.status) {
        SessionProposal.Status.PENDING -> if (mine) L("Waiting") else L("New invite")
        SessionProposal.Status.ACCEPTED -> L("Confirmed")
        SessionProposal.Status.DECLINED -> L("Declined")
        SessionProposal.Status.COUNTERED -> L("Other times suggested")
        SessionProposal.Status.CANCELLED -> L("Cancelled")
    }
    val confirmed = s.status == SessionProposal.Status.ACCEPTED
    AnimatedContent(
        targetState = Triple(text, waitingOnMe, confirmed),
        modifier = modifier,
        transitionSpec = { fadeIn(Motion.gentle()).togetherWith(fadeOut(Motion.gentle())) },
        label = "sessionStatus",
    ) { (t, filled, green) ->
        Text(
            t,
            Modifier
                .background(
                    when {
                        green -> p.like
                        filled -> p.lime
                        else -> p.canvas
                    },
                    CircleShape,
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
            style = TextStyles.footnote.bold,
            color = when {
                green -> p.onLike
                filled -> p.onLime
                else -> p.body
            },
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** The moment, as one block: the day, then the hour in the same size, then how far off it is. */
@Composable
private fun ConfirmedDate(at: Instant, modifier: Modifier = Modifier) {
    val p = DS.palette
    Column(modifier.semantics(mergeDescendants = true) { }) {
        Text(at.sessionDay, style = display(32f), color = p.ink)
        Text(DateText.time(at), style = display(32f), color = p.ink)
        at.sessionCountdown?.let {
            Text(it, Modifier.padding(top = DS.Space.sm), style = TextStyles.subheadline.semibold, color = p.mute)
        }
    }
}

// MARK: Block

/** One white block for what the person can do, by where the session stands. */
@Composable
private fun Block(
    s: SessionProposal,
    mine: Boolean,
    busy: Boolean,
    name: String,
    canAnswer: Boolean,
    selected: Instant?,
    onSelect: (Instant) -> Unit,
    onCounter: () -> Unit,
    onDecline: () -> Unit,
    onSafety: () -> Unit,
    onOpenChat: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val p = DS.palette
    val block = modifier.fillMaxWidth().background(p.canvas, RoundedCornerShape(DS.Radius.xl))
    when {
        s.status == SessionProposal.Status.PENDING && !mine -> Column(
            block.padding(DS.Space.lg),
            verticalArrangement = Arrangement.spacedBy(DS.Space.md),
        ) {
            if (s.options.size > 1) {
                Text(
                    L("Pick the time that works for you"),
                    Modifier.semantics { heading() },
                    style = TextStyles.footnote.bold,
                    color = p.mute,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
                s.options.forEach { d ->
                    val on = selected == d
                    PressScaleButton(
                        onClick = {
                            Haptics.select()
                            onSelect(d)
                        },
                        modifier = Modifier.semantics { this.selected = on },
                        scale = 0.98f,
                    ) {
                        TimeTile(d, selectable = true, on = on)
                    }
                }
            }
            // Stacked, full width: each label on one line in every language.
            Column(Modifier.padding(top = DS.Space.xs), verticalArrangement = Arrangement.spacedBy(DS.Space.xs)) {
                DrafftButton(
                    L("Other times"),
                    onClick = onCounter,
                    kind = DrafftButtonKind.SECONDARY,
                    enabled = !busy && canAnswer,
                )
                QuietLink(L("Not this time"), enabled = !busy && canAnswer, onClick = onDecline)
            }
        }

        s.status == SessionProposal.Status.PENDING -> Column(
            block.padding(DS.Space.lg),
            verticalArrangement = Arrangement.spacedBy(DS.Space.md),
        ) {
            if (s.options.size > 1) {
                Text(
                    L("%d times offered", s.options.size),
                    Modifier.semantics { heading() },
                    style = TextStyles.footnote.bold,
                    color = p.mute,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
                s.options.forEach { TimeTile(it, selectable = false, on = false) }
            }
            Text(
                L("%s hasn't answered yet. They can pick one of your times or suggest others.", name),
                style = TextStyles.footnote,
                color = p.body,
            )
        }

        s.status == SessionProposal.Status.ACCEPTED -> Column(block.padding(horizontal = DS.Space.lg)) {
            if (onOpenChat != null) {
                ActionRow(L("Open chat"), "chat-round-line", onOpenChat)
                Box(
                    Modifier
                        .padding(start = 52.dp)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(p.hairline),
                )
            }
            ActionRow(L("Meet safely"), "shield-check", onSafety)
        }

        // Declined, countered, cancelled: what was proposed stays readable, as plain rows (the agreed time
        // when there was one).
        else -> {
            val dates = s.chosen?.let { listOf(it) } ?: s.options
            Column(block.padding(DS.Space.lg), verticalArrangement = Arrangement.spacedBy(DS.Space.md)) {
                if (dates.size > 1) {
                    Text(
                        L("%d times offered", dates.size),
                        Modifier.semantics { heading() },
                        style = TextStyles.footnote.bold,
                        color = p.mute,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
                    dates.forEach { TimeTile(it, selectable = false, on = false) }
                }
            }
        }
    }
}

@Composable
private fun ActionRow(title: String, icon: String, onClick: () -> Unit) {
    val p = DS.palette
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) {
                Haptics.tap()
                onClick()
            },
        horizontalArrangement = Arrangement.spacedBy(DS.Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .background(p.canvasSoft, CircleShape)
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            DrafftIcon(icon, size = 18.dp, tint = p.ink)
        }
        Text(title, Modifier.weight(1f), style = TextStyles.headline, color = p.ink)
        DrafftIcon("alt-arrow-right", size = 16.dp, tint = p.mute)
    }
}

/**
 * One offered time: the day on the left, the hour on the right, in the same size. Picked, it fills with
 * the accent (selection is a fill, never a frame).
 */
@Composable
private fun TimeTile(d: Instant, selectable: Boolean, on: Boolean) {
    val p = DS.palette
    // Selection: a quick ease-out on the colour, never a spring.
    val fill by animateColorAsState(if (on) p.lime else p.canvasSoft, Motion.select(), label = "timeFill")
    val ink by animateColorAsState(if (on) p.onLime else p.ink, Motion.select(), label = "timeInk")
    val day = d.sessionDay
    val time = DateText.time(d)
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 60.dp)
            .background(fill, RoundedCornerShape(DS.Radius.lg))
            .padding(horizontal = DS.Space.lg, vertical = DS.Space.md)
            .clearAndSetSemantics { contentDescription = "$day, $time" },
        horizontalArrangement = Arrangement.spacedBy(DS.Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(day, style = TextStyles.headline, color = ink)
            d.sessionCountdown?.let { Text(it, Modifier.alpha(0.65f), style = TextStyles.footnote, color = ink) }
        }
        Text(time, style = TextStyles.headline.monospacedDigits, color = ink, maxLines = 1, softWrap = false)
        if (selectable) CheckDisc(isOn = on, onLimeFill = true)
    }
}

/** A quiet full-width text action ("Not this time", "Cancel session"). */
@Composable
private fun QuietLink(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextLinkButton(onClick = onClick, modifier = modifier, enabled = enabled, minHeight = 48.dp, fullWidth = true) {
        Text(
            text,
            style = TextStyles.subheadline.semibold,
            color = if (enabled) DS.palette.mute else LocalContentColor.current,
            textAlign = TextAlign.Center,
        )
    }
}

// MARK: Pinned action

/**
 * The one thing to do. Nothing when there is nothing to do: a page opened from the chat needs no "Open
 * chat", Back is there.
 */
@Composable
private fun Footer(
    session: SessionProposal,
    mine: Boolean,
    busy: Boolean,
    selected: Instant?,
    name: String,
    chatID: String,
    onConfirm: (Instant) -> Unit,
    onOpenChat: (() -> Unit)?,
) {
    val content: (@Composable () -> Unit)? = when {
        session.status == SessionProposal.Status.PENDING && !mine -> {
            {
                DrafftButton(onClick = { selected?.let(onConfirm) }, enabled = selected != null && !busy) {
                    AnimatedContent(
                        targetState = selected?.let { confirmTitle(it) to confirmTimeTitle(it) }
                            ?: (L("Pick a time above") to L("Pick a time above")),
                        transitionSpec = { fadeIn(Motion.gentle()).togetherWith(fadeOut(Motion.gentle())) },
                        label = "confirmTitle",
                    ) { title ->
                        // One line in every language: when the day doesn't fit, the time alone.
                        FirstThatFits {
                            Text(title.first, maxLines = 1, softWrap = false, textAlign = TextAlign.Center)
                            Text(title.second, maxLines = 2, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
        session.status == SessionProposal.Status.ACCEPTED -> {
            { CalendarButton(session = session, partner = name, chatID = chatID) }
        }
        onOpenChat != null -> {
            {
                DrafftButton(onClick = onOpenChat) {
                    DrafftIcon("chat-round-line", size = 20.dp, tint = LocalContentColor.current)
                    Text(L("Open chat"), maxLines = 2)
                }
            }
        }
        else -> null
    }
    if (content != null) {
        Box(Modifier.padding(start = DS.Space.lg, end = DS.Space.lg, top = DS.Space.md, bottom = DS.Space.sm)) { content() }
    }
}

/** "Confirm Sat 12, 9:00" for the time picked. */
private fun confirmTitle(d: Instant): String = L("Confirm %s, %s", DateText.weekdayShortDay(d), DateText.time(d))

/** "Confirm 9:00": the short form, when the day doesn't fit on the button's line. */
private fun confirmTimeTitle(d: Instant): String = L("Confirm %s", DateText.time(d))
