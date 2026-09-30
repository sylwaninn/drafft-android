package so.drafft.app.feature.me

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.Instant
import so.drafft.app.feature.auth.LegalDoc
import so.drafft.core.data.platform.Haptics
import so.drafft.core.model.DateText
import so.drafft.core.model.L
import so.drafft.core.model.Profile
import so.drafft.core.model.SessionProposal
import so.drafft.core.ui.LocalAppModel
import so.drafft.core.ui.components.ConfirmAction
import so.drafft.core.ui.components.DrafftButton
import so.drafft.core.ui.components.DrafftConfirm
import so.drafft.core.ui.components.EdgeBars
import so.drafft.core.ui.components.LocalSheetDismiss
import so.drafft.core.ui.components.Photo
import so.drafft.core.ui.components.PressScaleButton
import so.drafft.core.ui.components.SheetBlock
import so.drafft.core.ui.components.SheetNavBar
import so.drafft.core.ui.components.draftBlock
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.NightSurface
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.bold
import so.drafft.core.ui.theme.branded
import so.drafft.core.ui.theme.display
import so.drafft.core.ui.theme.semibold

// Port of Drafft/Features/Me/MeInfoSheets.swift.

/**
 * The page of a sheet from You: the inline navigation bar (title, close on the right, an optional
 * back on the left) over a progressive blur, an optional pinned [bottomBar], and [content] scrolling
 * under both ([content] gets the bars' heights as padding). The sheet already keeps clear of the
 * system bars and the keyboard, so the bars take no insets of their own.
 */
@Composable
internal fun SheetPage(
    title: String,
    scroll: ScrollState,
    modifier: Modifier = Modifier,
    onClose: () -> Unit = LocalSheetDismiss.current,
    leading: (@Composable () -> Unit)? = null,
    bottomBar: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    EdgeBars(
        scroll = scroll,
        modifier = modifier.fillMaxSize().background(DS.palette.canvasSoft),
        topBar = { SheetNavBar(title, onClose = onClose, leading = leading) },
        bottomBar = bottomBar,
        navigationEdge = true,
        windowInsets = WindowInsets(0, 0, 0, 0),
        content = content,
    )
}

/** SwiftUI's `Label`: an SF Symbol before the text, both in [color]. */
@Composable
internal fun IconLabel(
    text: String,
    symbol: String,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyles.body,
    color: Color = LocalContentColor.current,
    maxLines: Int = Int.MAX_VALUE,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        DrafftIcon(symbol, size = symbolSize(style), tint = color)
        Text(text, style = style, color = color, maxLines = maxLines)
    }
}

/** The box an SF Symbol set in [style] takes (a bit larger than the type, like core:ui's). */
internal fun symbolSize(style: TextStyle): Dp = (style.fontSize.value * 1.2f).dp

/** `ProgressView()` inside a button: a small spinner in the label's colour. */
@Composable
internal fun ButtonSpinner(color: Color = LocalContentColor.current) {
    CircularProgressIndicator(Modifier.size(20.dp), color = color, strokeWidth = 2.dp)
}

/**
 * Chrome for read-only sheets from You (nothing to submit, so no pinned button): inline title, close
 * on the right, white blocks on sage.
 */
@Composable
fun MeInfoSheet(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    SheetPage(title, scroll, modifier) { bars ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(bars)
                .padding(start = DS.Space.lg, end = DS.Space.lg, top = DS.Space.sm, bottom = DS.Space.xxl),
            verticalArrangement = Arrangement.spacedBy(DS.Space.md),
            content = content,
        )
    }
}

/** Round icon badge used by the rows below. */
@Composable
private fun RowBadge(symbol: String) {
    Box(
        Modifier
            .size(36.dp)
            .background(DS.palette.canvasSoft, CircleShape)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        DrafftIcon(symbol, size = 17.dp, tint = DS.palette.ink)
    }
}

@Composable
private fun RowSeparator() {
    Box(
        Modifier
            .padding(start = 52.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(DS.palette.hairline),
    )
}

// MARK: - Blocked people

@Composable
fun BlockedPeopleSheet(modifier: Modifier = Modifier) {
    val app = LocalAppModel.current
    var pending by remember { mutableStateOf<Profile?>(null) }
    val blocked = app.blocked
    val p = DS.palette

    MeInfoSheet(L("Blocked people"), modifier) {
        AnimatedContent(
            targetState = blocked.isEmpty(),
            transitionSpec = { fadeIn(Motion.gentle()) togetherWith fadeOut(Motion.gentle()) },
            label = "blocked",
        ) { empty ->
            if (empty) {
                SheetBlock(Modifier.semantics(mergeDescendants = true) { }) {
                    RowBadge("hand.raised.fill")
                    Text(L("No one blocked."), style = TextStyles.headline, color = p.ink)
                    Text(L("You can block someone from their profile or a chat."), style = TextStyles.subheadline, color = p.body)
                }
            } else {
                SheetBlock {
                    Text(
                        L("They can't see your profile or message you, and you won't see them."),
                        style = TextStyles.subheadline,
                        color = p.body,
                    )
                    Column(Modifier.animateContentSize(Motion.snappy())) {
                        blocked.forEachIndexed { index, person ->
                            key(person.id) {
                                if (index > 0) RowSeparator()
                                BlockedRow(person) {
                                    Haptics.tap()
                                    pending = person
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val person = pending
    DrafftConfirm(
        visible = person != null,
        onDismissRequest = { pending = null },
        icon = "hand.raised.slash.fill",
        title = person?.let { L("Unblock %s?", it.name) } ?: L("Unblock?"),
        message = L("You'll see each other in Discover again. Your old chat doesn't come back."),
        actions = if (person == null) {
            emptyList()
        } else {
            listOf(ConfirmAction(L("Unblock")) {
                Haptics.success()
                app.unblock(person)
            })
        },
    )
}

@Composable
private fun BlockedRow(person: Profile, onUnblock: () -> Unit) {
    val p = DS.palette
    Row(
        Modifier.padding(vertical = DS.Space.xs),
        horizontalArrangement = Arrangement.spacedBy(DS.Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Photo(person.portrait, Modifier.size(36.dp).clip(CircleShape), side = 36.dp)
        Text(person.name, Modifier.weight(1f), style = TextStyles.body.semibold, color = p.ink)
        PressScaleButton(
            onClick = onUnblock,
            modifier = Modifier.defaultMinSize(minHeight = 44.dp),
            scale = 0.94f,
            contentDescription = L("Unblock %s", person.name),
        ) {
            Text(
                L("Unblock"),
                Modifier
                    .defaultMinSize(minHeight = 36.dp)
                    .background(p.canvasSoft, CircleShape)
                    .padding(horizontal = DS.Space.md, vertical = 9.dp),
                style = TextStyles.footnote.semibold,
                color = p.ink,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

// MARK: - Safety tips

/**
 * One set of safety advice, used everywhere: the Safety tips page, the session invite and the
 * session confirmation. Same words in each place, so people learn them.
 */
object SafetyTips {
    data class Tip(val icon: String, val title: String, val detail: String)

    /** Before and during a first session. */
    val meeting: List<Tip>
        get() = listOf(
            Tip("figure.run", L("Meet where people train"), L("A busy track, park, gym or club session, with others around.")),
            Tip("person.2.fill", L("Tell a friend"), L("Share who you're meeting, where and when. Check in with them after.")),
            Tip("bicycle", L("Get there on your own"), L("Make your own way there and back. Your address can wait.")),
            Tip("map.fill", L("Stay on routes you know"), L("For a run or a ride, pick a busy route in daylight and keep your phone charged.")),
            Tip("bubble.left.and.bubble.right.fill", L("Keep the chat in drafft"), L("Stay in the app until you know them. Never send money.")),
            Tip("door.left.hand.open", L("Trust your gut"), L("You can end a session anytime, no explanation needed.")),
        )

    val report: Tip
        get() = Tip("flag.fill", L("Report anything off"), L("Tap Report or block on their profile or in the chat. Reports are confidential."))
}

/** Tips as rows: round badge, title, one line of detail, hairlines between. */
@Composable
fun SafetyTipRows(
    tips: List<SafetyTips.Tip> = SafetyTips.meeting,
    modifier: Modifier = Modifier,
) {
    val p = DS.palette
    Column(modifier) {
        tips.forEachIndexed { index, tip ->
            key(tip.title) {
                if (index > 0) RowSeparator()
                Row(
                    Modifier
                        .padding(vertical = DS.Space.md)
                        .semantics(mergeDescendants = true) { },
                    horizontalArrangement = Arrangement.spacedBy(DS.Space.md),
                ) {
                    RowBadge(tip.icon)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(branded(tip.title, brandWeight = FontWeight.ExtraBold), style = TextStyles.subheadline.semibold, color = p.ink)
                        Text(tip.detail, style = TextStyles.footnote, color = p.body)
                    }
                }
            }
        }
    }
}

@Composable
fun SafetyTipsSheet(modifier: Modifier = Modifier) {
    MeInfoSheet(L("Safety tips"), modifier) {
        SheetBlock(title = L("Meeting someone for the first time")) {
            SafetyTipRows(tips = SafetyTips.meeting + SafetyTips.report)
        }
    }
}

/** Shown right after you confirm a session time: the plan in one line, then the safety tips. */
@Composable
fun SessionSafetySheet(
    session: SessionProposal,
    date: Instant,
    partner: String,
    modifier: Modifier = Modifier,
) {
    val dismiss = LocalSheetDismiss.current
    val scroll = rememberScrollState()
    val p = DS.palette
    SheetPage(
        title = L("Meet safely"),
        scroll = scroll,
        modifier = modifier,
        bottomBar = {
            DrafftButton(
                L("Got it"),
                onClick = dismiss,
                modifier = Modifier.padding(start = DS.Space.xl, end = DS.Space.xl, top = DS.Space.md),
            )
        },
    ) { bars ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(bars)
                .padding(start = DS.Space.lg, end = DS.Space.lg, top = DS.Space.sm, bottom = DS.Space.xl),
            verticalArrangement = Arrangement.spacedBy(DS.Space.md),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .draftBlock(p.night)
                    .padding(DS.Space.xl)
                    .semantics(mergeDescendants = true) { },
                verticalArrangement = Arrangement.spacedBy(DS.Space.sm),
            ) {
                NightSurface {
                    IconLabel(
                        L("Session confirmed"),
                        "checkmark.circle.fill",
                        style = TextStyles.subheadline.semibold,
                        color = p.accentOnNight,
                    )
                    Text(L("%s with %s", session.sport.displayName, partner), style = display(28f), color = Color.White)
                    Text(
                        L("%s at %s", DateText.format("EEEEdMMMM", date), DateText.time(date)),
                        style = TextStyles.headline,
                        color = Color.White.copy(alpha = 0.8f),
                    )
                }
            }
            SheetBlock(title = L("Before you go")) {
                SafetyTipRows()
            }
        }
    }
}

// MARK: - Legal documents

@Composable
fun LegalDocsListSheet(modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val p = DS.palette

    fun icon(doc: LegalDoc): String = when (doc) {
        LegalDoc.TERMS -> "doc.text.fill"
        LegalDoc.PRIVACY -> "lock.fill"
        LegalDoc.COMMUNITY -> "person.3.fill"
    }

    MeInfoSheet(L("Terms & privacy"), modifier) {
        SheetBlock {
            Column {
                LegalDoc.entries.forEachIndexed { index, doc ->
                    if (index > 0) RowSeparator()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 44.dp)
                            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) {
                                Haptics.tap()
                                uriHandler.openUri(doc.url())
                            }
                            .padding(vertical = DS.Space.md),
                        horizontalArrangement = Arrangement.spacedBy(DS.Space.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RowBadge(icon(doc))
                        Text(doc.title, Modifier.weight(1f), style = TextStyles.body.semibold, color = p.ink)
                        DrafftIcon("chevron.right", size = symbolSize(TextStyles.footnote), tint = p.mute)
                    }
                }
            }
        }
    }
}

/** A thin hairline between rows, full width (the iPhone's `Divider`). */
@Composable
internal fun Hairline(modifier: Modifier = Modifier, start: Dp = 0.dp) {
    Box(
        modifier
            .padding(start = start)
            .fillMaxWidth()
            .height(1.dp)
            .background(DS.palette.hairline),
    )
}
