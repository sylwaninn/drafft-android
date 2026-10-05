package so.drafft.app.feature.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.UUID
import org.koin.compose.koinInject
import so.drafft.core.data.AppModel
import so.drafft.core.data.sessions.SessionStore
import so.drafft.core.data.sessions.from
import so.drafft.core.model.DateText
import so.drafft.core.model.L
import so.drafft.core.model.SessionProposal
import so.drafft.core.ui.LocalAppModel
import so.drafft.core.ui.components.Avatar
import so.drafft.core.ui.components.BlurredNavigationEdge
import so.drafft.core.ui.components.DrafftButton
import so.drafft.core.ui.components.EmptyStateArt
import so.drafft.core.ui.components.EmptyStateView
import so.drafft.core.ui.components.GlassCircleButton
import so.drafft.core.ui.components.HidesTabBar
import so.drafft.core.ui.components.LocalTabBarInset
import so.drafft.core.ui.components.NightBlock
import so.drafft.core.ui.components.SheetNavBar
import so.drafft.core.ui.components.TabHeader
import so.drafft.core.ui.components.TabTitle
import so.drafft.core.ui.components.TopBar
import so.drafft.core.ui.components.trackingScrollOffset
import so.drafft.core.ui.navigation.LocalNavStack
import so.drafft.core.ui.navigation.NavStackHost
import so.drafft.core.ui.navigation.rememberNavStack
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.bold
import so.drafft.core.ui.theme.display
import so.drafft.core.ui.theme.heavy
import so.drafft.core.ui.theme.monospacedDigits
import so.drafft.core.ui.theme.semibold

/** The Sessions tab's own page, the root of its stack. */
private data object SessionsRoot

/**
 * Sessions tab: the next confirmed session as a feature card, then everything else coming up, an answer
 * to give first. From the server (`upcoming_sessions`, `SessionStore`), live. It follows sessions, it
 * never creates one: a session is proposed from a chat only. A row opens the session's own page.
 *
 * Pages and chats open inside this tab, in its own stack, so Back returns to Sessions.
 */
@Composable
fun SessionsView(modifier: Modifier = Modifier) {
    val stack = rememberNavStack(SessionsRoot)
    // A page pushed from Sessions takes the whole screen: no tab bar under it.
    if (stack.canPop) HidesTabBar()
    NavStackHost(stack, modifier) { route -> ChatStackScreen(route) { SessionsPage() } }
}

@Composable
private fun SessionsPage() {
    val app = LocalAppModel.current
    val store = koinInject<SessionStore>()
    val stack = LocalNavStack.current
    val scroll = rememberScrollState()
    val offset = scroll.trackingScrollOffset()

    // Read again whenever the tab shows (Realtime and foreground keep it current meanwhile). The tabs
    // stay composed, so "shows" is the tab being picked, or coming back from a page pushed over it.
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
            ) {
                SessionsList(
                    matchID = null,
                    onOpen = { stack.push(SessionRoute(it, opensChat = true)) },
                    empty = {
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
                    },
                )
            }
        }
    }
}

/**
 * Every session with one person, pushed from their chat: the same list as the tab, filtered. It only
 * lists and opens; proposing is the chat's session button. No "Open chat" on a page opened from here:
 * Back returns to this list, then to the chat.
 */
@Composable
fun PersonSessionsView(chatID: String, name: String, modifier: Modifier = Modifier) {
    val store = koinInject<SessionStore>()
    val stack = LocalNavStack.current
    val scroll = rememberScrollState()
    val match = remember(chatID) { runCatching { UUID.fromString(chatID) }.getOrNull() }
    LaunchedEffect(chatID) { store.refresh() }

    BlurredNavigationEdge(
        scroll = scroll,
        modifier = modifier.fillMaxSize().background(DS.palette.canvasSoft),
        navigationBar = {
            SheetNavBar(
                title = L("Sessions with %s", name),
                onClose = null,
                leading = { GlassCircleButton("alt-arrow-left", onClick = { stack.pop() }, contentDescription = L("Back")) },
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val pageHeight = maxHeight
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(padding)
                    .padding(horizontal = DS.Space.lg)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                    .padding(bottom = DS.Space.xxl),
            ) {
                // A chat id that isn't a match's (a sample) lists nothing, rather than every session.
                SessionsList(
                    matchID = match ?: UUID(0, 0),
                    onOpen = { stack.push(SessionRoute(it)) },
                    empty = {
                        Box(Modifier.fillMaxWidth().height(pageHeight * 0.7f), contentAlignment = Alignment.Center) {
                            EmptyStateView(
                                art = EmptyStateArt.sessions,
                                title = L("No sessions with %s yet.", name),
                                message = L("Propose one from the chat."),
                            )
                        }
                    },
                )
            }
        }
    }
}

/** One upcoming session, with who it's with and whose turn it is. */
@Immutable
private data class Item(
    val session: SessionProposal,
    val name: String,
    val photo: String,
    val mine: Boolean,
    /** News: an answer is expected or the other person changed it since it was last opened. */
    val news: Boolean,
)

/**
 * The sessions, shared by the tab and a person's list: the next confirmed one as a feature card (on the
 * tab), then one list. One container, no category cards: what needs an answer comes first, then by
 * date, and each row says what it is (the sport, first), with whom, and when.
 */
@Composable
private fun SessionsList(
    /** Only this match's sessions (a person's list), or all of them (the tab). */
    matchID: UUID?,
    onOpen: (UUID) -> Unit,
    empty: @Composable () -> Unit,
) {
    val app = LocalAppModel.current
    val store = koinInject<SessionStore>()
    val items = store.upcoming.mapNotNull { row ->
        if (matchID != null && row.matchID != matchID) return@mapNotNull null
        val session = SessionProposal.from(row) ?: return@mapNotNull null
        val profile = app.conversation(row.matchID.toString().lowercase())?.profile
        Item(
            session = session,
            name = row.partner?.name ?: profile?.name ?: "",
            photo = row.partner?.photo ?: profile?.portrait ?: "",
            mine = store.isMine(row),
            news = store.needsAttention(row),
        )
    }
    // The next confirmed session, as the feature card (the tab only).
    val featured = if (matchID == null) items.firstOrNull { it.session.status == SessionProposal.Status.ACCEPTED } else null
    // Everything else: an answer to give first, then by date.
    val listed = items.filter { it.session.id != featured?.session?.id }
        .sortedWith(compareBy<Item> { !(it.session.status == SessionProposal.Status.PENDING && !it.mine) }.thenBy { it.session.date })

    Column(verticalArrangement = Arrangement.spacedBy(DS.Space.md)) {
        if (items.isEmpty()) {
            empty()
        } else {
            featured?.let { NextUp(it, onOpen = { onOpen(it.session.id) }) }
            if (listed.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(DS.palette.canvas, RoundedCornerShape(DS.Radius.xl))
                        .padding(horizontal = DS.Space.lg),
                ) {
                    listed.forEachIndexed { i, item ->
                        key(item.session.id) {
                            if (i > 0) {
                                Box(
                                    Modifier
                                        .padding(start = 64.dp)
                                        .fillMaxWidth()
                                        .height(1.dp)
                                        .background(DS.palette.hairline),
                                )
                            }
                            SessionRow(item, onOpen = { onOpen(item.session.id) })
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
    NightBlock(Modifier.fillMaxWidth(), radius = DS.Radius.xl + 4.dp) {
        Column(Modifier.fillMaxWidth().padding(DS.Space.xl - 4.dp), verticalArrangement = Arrangement.spacedBy(DS.Space.lg)) {
            // How far off it is on the left, where it stands on the right: confirmed, in the sports' green.
            Row(verticalAlignment = Alignment.CenterVertically) {
                s.date.sessionCountdown?.let { soon ->
                    Box(
                        Modifier
                            .height(32.dp)
                            .background(Color.White.copy(alpha = 0.12f), CircleShape)
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(soon, style = TextStyles.footnote.semibold, color = Color.White.copy(alpha = 0.8f), maxLines = 1, softWrap = false)
                    }
                }
                Spacer(Modifier.weight(1f).padding(start = DS.Space.sm))
                Row(
                    Modifier
                        .height(32.dp)
                        .background(p.like, CircleShape)
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DrafftIcon("check", size = 13.dp, tint = p.onLike)
                    Text(L("Confirmed"), style = TextStyles.footnote.bold, color = p.onLike, maxLines = 1, softWrap = false)
                }
            }

            // The moment: the day as the title, the hour under it.
            Column(verticalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
                Text(s.date.sessionDay, style = display(32f), color = Color.White)
                Text(s.timeText, style = TextStyles.title2.semibold.monospacedDigits, color = Color.White)
            }

            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.12f)))

            // The sport and the person are one sentence, at one level: "[ski] Ski with [photo] Dylan".
            Row(horizontalArrangement = Arrangement.spacedBy(DS.Space.sm), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(32.dp)
                        .background(Color.White, CircleShape)
                        .clearAndSetSemantics { },
                    contentAlignment = Alignment.Center,
                ) {
                    DrafftIcon(s.sport.symbol, size = 18.dp, tint = p.night)
                }
                Text(s.sport.displayName, style = TextStyles.headline, color = Color.White)
                Text(L("with"), style = TextStyles.headline, color = Color.White.copy(alpha = 0.7f), maxLines = 1, softWrap = false)
                Avatar(item.photo, size = 32.dp)
                Text(item.name, Modifier.weight(1f, fill = false), style = TextStyles.headline, color = Color.White)
            }

            DrafftButton(L("Open session"), onClick = onOpen)
        }
    }
}

// MARK: Rows

/**
 * One session: its sport as the mark and the title, with whom under it; on the right the state at the top,
 * then when. Nothing shares a line with the person's name, so it never breaks.
 */
@Composable
private fun SessionRow(item: Item, onOpen: () -> Unit) {
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
        Box(
            Modifier
                .size(52.dp)
                .background(p.canvasSoft, CircleShape)
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            DrafftIcon(s.sport.symbol, size = 26.dp, tint = p.ink)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(s.sport.displayName, style = TextStyles.headline, color = p.ink)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(L("with"), style = TextStyles.subheadline, color = p.body, maxLines = 1, softWrap = false)
                Avatar(item.photo, size = 20.dp)
                Text(item.name, Modifier.weight(1f, fill = false), style = TextStyles.subheadline, color = p.body)
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StateChip(item)
            When(s)
        }
        // News the person hasn't looked at (the other person confirmed it): a red dot, inside the row.
        if (item.news && s.status == SessionProposal.Status.ACCEPTED) {
            val new = L("New")
            Box(
                Modifier
                    .size(10.dp)
                    .background(p.negative, CircleShape)
                    .semantics { contentDescription = new },
            )
        }
    }
}

/** "Reply" when the answer is the person's, "Waiting" when it is the other's; nothing once confirmed. */
@Composable
private fun StateChip(item: Item) {
    if (item.session.status != SessionProposal.Status.PENDING) return
    val p = DS.palette
    Text(
        if (item.mine) L("Waiting") else L("Reply"),
        Modifier
            .background(if (item.mine) p.canvasSoft else p.lime, CircleShape)
            .padding(horizontal = 9.dp, vertical = 3.dp),
        style = TextStyles.caption.heavy,
        color = if (item.mine) p.body else p.onLime,
        maxLines = 1,
        softWrap = false,
    )
}

/** The day over the hour, or the first day over how many times are offered. */
@Composable
private fun When(s: SessionProposal) {
    val p = DS.palette
    val multiple = s.status == SessionProposal.Status.PENDING && s.options.size > 1
    val day = DateText.weekdayShortDayMonth(s.date)
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            if (multiple) L("From %s", day) else day,
            style = TextStyles.footnote,
            color = p.body,
            maxLines = 1,
            softWrap = false,
        )
        Text(
            if (multiple) L("%d times", s.options.size) else s.timeText,
            style = TextStyles.headline.monospacedDigits,
            color = p.ink,
            maxLines = 1,
            softWrap = false,
        )
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
