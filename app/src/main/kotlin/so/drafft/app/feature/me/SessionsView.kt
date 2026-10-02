package so.drafft.app.feature.me

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import org.koin.compose.koinInject
import so.drafft.app.feature.chat.CalendarButton
import so.drafft.app.feature.chat.ChatRoute
import so.drafft.app.feature.chat.ChatView
import so.drafft.core.data.AppModel
import so.drafft.core.data.sessions.SessionStore
import so.drafft.core.data.sessions.from
import so.drafft.core.model.DateText
import so.drafft.core.model.L
import so.drafft.core.model.SessionProposal
import so.drafft.core.ui.LocalAppModel
import so.drafft.core.ui.components.Avatar
import so.drafft.core.ui.components.DrafftButton
import so.drafft.core.ui.components.EmptyStateArt
import so.drafft.core.ui.components.EmptyStateView
import so.drafft.core.ui.components.LocalTabBarInset
import so.drafft.core.ui.components.NightBlock
import so.drafft.core.ui.components.TabHeader
import so.drafft.core.ui.components.TabTitle
import so.drafft.core.ui.components.TopBar
import so.drafft.core.ui.components.trackingScrollOffset
import so.drafft.core.ui.navigation.LocalNavStack
import so.drafft.core.ui.components.HidesTabBar
import so.drafft.core.ui.navigation.NavStackHost
import so.drafft.core.ui.navigation.rememberNavStack
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.bold
import so.drafft.core.ui.theme.display
import so.drafft.core.ui.theme.heavy
import so.drafft.core.ui.theme.semibold

/** The Sessions tab's own page, the root of its stack. */
private data object SessionsRoot

/** One upcoming session, with who it's with and whose turn it is. */
@Immutable
private data class Item(
    val session: SessionProposal,
    val chatID: String,
    val name: String,
    val photo: String,
    val mine: Boolean,
)

/**
 * Sessions tab: the next confirmed session as a feature card, invites that still need a time, then
 * everything else coming up. From the server (`upcoming_sessions`, `SessionStore`), live.
 *
 * Chats open inside this tab, in its own stack, so Back returns to Sessions.
 */
@Composable
fun SessionsView(modifier: Modifier = Modifier) {
    val stack = rememberNavStack(SessionsRoot)
    // A chat pushed from Sessions takes the whole screen: no tab bar under it.
    if (stack.canPop) HidesTabBar()
    NavStackHost(stack, modifier) { route ->
        when (route) {
            is ChatRoute -> ChatView(conversationID = route.chatID, focusSession = route.sessionID)
            else -> SessionsPage()
        }
    }
}

@Composable
private fun SessionsPage() {
    val app = LocalAppModel.current
    val store = koinInject<SessionStore>()
    val stack = LocalNavStack.current
    val scroll = rememberScrollState()
    val offset = scroll.trackingScrollOffset()

    val items by remember(app, store) {
        derivedStateOf {
            store.upcoming.mapNotNull { row ->
                val session = SessionProposal.from(row) ?: return@mapNotNull null
                val chatID = row.matchID.toString().lowercase()
                val profile = app.conversation(chatID)?.profile
                Item(
                    session = session,
                    chatID = chatID,
                    name = row.partner?.name ?: profile?.name ?: "",
                    photo = row.partner?.photo ?: profile?.portrait ?: "",
                    mine = store.isMine(row),
                )
            }
        }
    }
    val confirmed = items.filter { it.session.status == SessionProposal.Status.ACCEPTED }
    val pending = items.filter { it.session.status == SessionProposal.Status.PENDING }
    val open: (Item) -> Unit = { stack.push(ChatRoute(chatID = it.chatID, sessionID = it.session.id)) }

    // Read again whenever the tab shows (Realtime and foreground keep it current meanwhile). The
    // tabs stay composed, so "shows" is the tab being picked, or coming back from a chat.
    val shown = app.tab == AppModel.Tab.SESSIONS
    LaunchedEffect(shown) { if (shown) store.refresh() }

    TopBar(
        scroll = scroll,
        bar = { TabHeader(offset = { offset.value }) { TabTitle(L("Sessions")) } },
        modifier = Modifier.fillMaxSize().background(DS.palette.canvasSoft),
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val pageHeight = maxHeight
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(padding)
                    .padding(top = DS.Space.xs)
                    .padding(horizontal = DS.Space.lg)
                    .padding(bottom = DS.Space.xxl + LocalTabBarInset.current),
                verticalArrangement = Arrangement.spacedBy(DS.Space.md),
            ) {
                if (confirmed.isEmpty() && pending.isEmpty()) {
                    // The middle of the visible page, under the header.
                    Box(Modifier.fillMaxWidth().height(pageHeight * 0.8f), contentAlignment = Alignment.Center) {
                        EmptyStateView(
                            art = EmptyStateArt.sessions,
                            title = L("No sessions yet."),
                            message = L("Open a chat with a match and propose a session. Confirmed ones show up here."),
                        ) {
                            DrafftButton(L("Go to chats"), onClick = { app.tab = AppModel.Tab.CHATS }, fullWidth = false)
                        }
                    }
                } else {
                    confirmed.firstOrNull()?.let { NextUp(it, onOpen = { open(it) }) }
                    if (pending.isNotEmpty()) {
                        Group(L("Finding a time")) {
                            pending.forEachIndexed { i, item ->
                                androidx.compose.runtime.key(item.session.id) {
                                    PendingRow(item, onOpen = { open(item) })
                                    if (i < pending.size - 1) Separator()
                                }
                            }
                        }
                    }
                    val later = confirmed.drop(1)
                    if (later.isNotEmpty()) {
                        Group(L("Coming up")) {
                            later.forEachIndexed { i, item ->
                                androidx.compose.runtime.key(item.session.id) {
                                    ConfirmedRow(item, onOpen = { open(item) })
                                    if (i < later.size - 1) Separator()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// MARK: Next up

@Composable
private fun NextUp(item: Item, onOpen: () -> Unit) {
    val p = DS.palette
    val s = item.session
    NightBlock(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(DS.Space.xl), verticalArrangement = Arrangement.spacedBy(DS.Space.xl)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    countdown(s.date),
                    Modifier
                        .background(p.accentOnNight, CircleShape)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    style = TextStyles.caption.heavy,
                    color = p.onAccentOnNight,
                    maxLines = 1,
                    softWrap = false,
                )
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .size(44.dp)
                        .background(Color.White, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    DrafftIcon(s.sport.symbol, size = 22.dp, tint = p.night)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
                Text(s.displayTitle, style = display(30f), color = Color.White)
                Text(
                    L("%s at %s", DateText.format("EEEEdMMMM", s.date), s.timeText),
                    style = TextStyles.subheadline.semibold,
                    color = p.accentOnNight,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(DS.Space.sm), verticalAlignment = Alignment.CenterVertically) {
                Avatar(item.photo, size = 36.dp)
                Text(
                    L("With %s", item.name),
                    Modifier.weight(1f),
                    style = TextStyles.subheadline.semibold,
                    color = Color.White,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(DS.Space.sm), verticalAlignment = Alignment.CenterVertically) {
                DrafftButton(
                    onClick = onOpen,
                    modifier = Modifier.weight(1f),
                ) {
                    DrafftIcon("chat-round-line", size = 20.dp, tint = LocalContentColor.current)
                    Text(L("Open chat"), maxLines = 2, overflow = TextOverflow.Clip)
                }
                CalendarButton(session = s, partner = item.name, chatID = item.chatID, compact = true)
            }
        }
    }
}

/** "Today", "Tomorrow", "In 3 days", or the date when it's further out. */
private fun countdown(date: Instant): String {
    val zone = DateText.zone
    val today = LocalDate.now(zone)
    val day = date.atZone(zone).toLocalDate()
    if (day == today) return L("Today")
    if (day == today.plusDays(1)) return L("Tomorrow")
    val days = ChronoUnit.DAYS.between(today, day).toInt()
    return if (days < 7) L("In %d days", days) else L("Next up")
}

// MARK: Rows

@Composable
private fun DateColumn(date: Instant) {
    val p = DS.palette
    Column(Modifier.width(44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(DateText.format("EEE", date), style = TextStyles.caption.bold, color = p.body, maxLines = 1)
        Text(DateText.format("d", date), style = display(26f), color = p.ink, maxLines = 1)
    }
}

@Composable
private fun PendingRow(item: Item, onOpen: () -> Unit) {
    // Their invite waiting on you, or yours waiting on them.
    val p = DS.palette
    val s = item.session
    val mine = item.mine
    Row(
        Modifier
            .fillMaxWidth()
            .plainButton(onOpen)
            .padding(vertical = DS.Space.md),
        horizontalArrangement = Arrangement.spacedBy(DS.Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(item.photo, size = 44.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(L("%s with %s", s.sport.displayName, item.name), style = TextStyles.headline, color = p.ink)
            Text(
                if (s.options.size > 1) {
                    L("%d times offered", s.options.size)
                } else {
                    L("%s, %s", DateText.weekdayShortDay(s.date), s.timeText)
                },
                style = TextStyles.footnote,
                color = p.body,
            )
        }
        Text(
            if (mine) L("Waiting") else L("Your turn"),
            Modifier
                .background(if (mine) p.canvasSoft else p.lime, CircleShape)
                .padding(horizontal = 10.dp, vertical = 5.dp),
            style = TextStyles.caption.heavy,
            color = if (mine) p.body else p.onLime,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun ConfirmedRow(item: Item, onOpen: () -> Unit) {
    val p = DS.palette
    val s = item.session
    Row(
        Modifier
            .fillMaxWidth()
            .plainButton(onOpen)
            .padding(vertical = DS.Space.md),
        horizontalArrangement = Arrangement.spacedBy(DS.Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DateColumn(s.date)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // design-lint: allow truncation - the session's title, written by people (content, not copy)
            Text(s.displayTitle, style = TextStyles.headline, color = p.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(L("%s with %s", s.timeText, item.name), style = TextStyles.footnote, color = p.body)
        }
        Box(
            Modifier
                .size(36.dp)
                .background(p.lime, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            DrafftIcon(s.sport.symbol, size = 17.dp, tint = p.onLime)
        }
    }
}

/** A `.plain` button: the whole row takes the touch and dims while pressed, no ripple. */
@Composable
private fun Modifier.plainButton(onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    return this
        .graphicsLayer { alpha = if (pressed) 0.7f else 1f }
        .clickable(source, indication = null, role = Role.Button, onClick = onClick)
}

// MARK: Chrome

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    val p = DS.palette
    Column(
        Modifier
            .fillMaxWidth()
            .background(p.canvas, RoundedCornerShape(DS.Radius.xl))
            .padding(horizontal = DS.Space.lg)
            .padding(bottom = DS.Space.xs),
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
private fun Separator() {
    Box(
        Modifier
            .padding(start = 56.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(DS.palette.hairline),
    )
}
