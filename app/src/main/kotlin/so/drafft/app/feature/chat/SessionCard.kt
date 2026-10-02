package so.drafft.app.feature.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import so.drafft.app.feature.discover.FirstThatFits
import so.drafft.core.data.platform.Haptics
import so.drafft.core.model.DateText
import so.drafft.core.model.L
import so.drafft.core.model.SessionProposal
import so.drafft.core.ui.components.CheckDisc
import so.drafft.core.ui.components.ConfirmAction
import so.drafft.core.ui.components.DrafftButton
import so.drafft.core.ui.components.DrafftButtonKind
import so.drafft.core.ui.components.DrafftConfirm
import so.drafft.core.ui.components.FlowLayout
import so.drafft.core.ui.components.NightBlock
import so.drafft.core.ui.components.PressScaleButton
import so.drafft.core.ui.components.TextLinkButton
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.bold
import so.drafft.core.ui.theme.display
import so.drafft.core.ui.theme.semibold
import java.time.Instant

/**
 * A session invite in the chat. It carries 1 to 3 time options: the receiver picks one, suggests
 * other times (which sends a new card), or declines. Once agreed, it shows the chosen time.
 */
@Composable
fun SessionCard(
    session: SessionProposal,
    mine: Boolean,
    profileName: String,
    chatID: String,
    /** A change of the person's is on its way to the server: the buttons wait for it. */
    busy: Boolean = false,
    onPick: (Instant) -> Unit,
    onDecline: () -> Unit,
    onCounter: () -> Unit,
    onCancel: () -> Unit = {},
    onSafety: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // A single option is picked for them on arrival.
    var selected by remember(session.id) { mutableStateOf(if (session.options.size == 1) session.options.first() else null) }
    var confirmCancel by remember { mutableStateOf(false) }
    val canAnswer = !mine && session.status == SessionProposal.Status.PENDING
    val faded = session.status == SessionProposal.Status.COUNTERED || session.status == SessionProposal.Status.CANCELLED

    NightBlock(modifier.width(300.dp).alpha(if (faded) 0.6f else 1f)) {
        Column(Modifier.padding(DS.Space.xl), verticalArrangement = Arrangement.spacedBy(DS.Space.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(48.dp)
                        .background(Color.White, CircleShape)
                        .clearAndSetSemantics { },
                    contentAlignment = Alignment.Center,
                ) {
                    DrafftIcon(session.sport.symbol, size = 24.dp, tint = DS.palette.night)
                }
                Box(Modifier.weight(1f))
                StatusPill(session.status, mine)
            }

            Text(
                session.displayTitle,
                style = display(if (session.title.isEmpty()) 28f else 24f),
                color = Color.White,
            )

            Times(session, canAnswer, selected) { selected = it }

            session.discovery?.let { d ->
                // You teach when you sent an I_TEACH invite, or received a THEY_TEACH one.
                val youTeach = (d == SessionProposal.Discovery.I_TEACH) == mine
                Row(
                    Modifier
                        .background(DS.palette.accentOnNight, CircleShape)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(DS.Space.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DrafftIcon("stars", size = 14.dp, tint = DS.palette.onAccentOnNight)
                    Text(
                        if (youTeach) L("Discovery: you show %s the ropes", profileName) else L("Discovery: %s shows you the ropes", profileName),
                        style = TextStyles.caption.bold,
                        color = DS.palette.onAccentOnNight,
                    )
                }
            }

            if (session.tags.isNotEmpty()) {
                FlowLayout(spacing = 6.dp) {
                    session.tags.forEach { tag ->
                        Text(
                            tag,
                            Modifier
                                .background(Color.White.copy(alpha = 0.14f), CircleShape)
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            style = TextStyles.caption.bold,
                            color = Color.White,
                        )
                    }
                }
            }

            if (session.note.isNotEmpty()) {
                Text(
                    session.note,
                    Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(DS.Radius.md))
                        .padding(horizontal = DS.Space.md, vertical = DS.Space.sm),
                    style = TextStyles.subheadline,
                    color = Color.White.copy(alpha = 0.8f),
                )
            }

            Actions(session, mine, profileName, chatID, busy, selected, onPick, onDecline, onCounter, onSafety) {
                confirmCancel = true
            }
        }
    }

    DrafftConfirm(
        visible = confirmCancel,
        onDismissRequest = { confirmCancel = false },
        icon = "calendar-minus",
        title = L("Cancel this session?"),
        message = L("%s will be told it's off.", profileName),
        cancelTitle = L("Keep it"),
        actions = listOf(ConfirmAction(L("Cancel session"), ConfirmAction.Kind.DESTRUCTIVE) { onCancel() }),
    )
}

// MARK: Times

private enum class RowState { OPTION, SELECTED, AGREED }

@Composable
private fun Times(session: SessionProposal, canAnswer: Boolean, selected: Instant?, onSelect: (Instant) -> Unit) {
    val chosen = session.chosen
    if (session.status == SessionProposal.Status.ACCEPTED && chosen != null) {
        TimeRow(chosen, RowState.AGREED, canAnswer)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
        if (session.options.size > 1) {
            Text(
                if (canAnswer) L("Pick the time that works for you") else L("%d times offered", session.options.size),
                style = TextStyles.caption.bold,
                color = Color.White.copy(alpha = 0.6f),
            )
        }
        session.options.forEach { d ->
            if (canAnswer) {
                val on = selected == d
                PressScaleButton(
                    onClick = {
                        Haptics.select()
                        onSelect(d)
                    },
                    modifier = Modifier.semantics { this.selected = on },
                    scale = 0.98f,
                ) {
                    TimeRow(d, if (on) RowState.SELECTED else RowState.OPTION, canAnswer)
                }
            } else {
                TimeRow(d, RowState.OPTION, canAnswer)
            }
        }
    }
}

@Composable
private fun TimeRow(d: Instant, state: RowState, canAnswer: Boolean) {
    val on = state != RowState.OPTION
    val p = DS.palette
    // Selection: a quick ease-out on the colour, never a spring.
    val fill by animateColorAsState(
        if (state == RowState.SELECTED) p.selectedOnNight else Color.White.copy(alpha = 0.06f),
        Motion.select(),
        label = "timeRow",
    )
    val day = DateText.weekdayDayMonth(d)
    val time = DateText.time(d)
    Row(
        Modifier
            .fillMaxWidth()
            .background(fill, RoundedCornerShape(DS.Radius.md))
            .padding(DS.Space.sm)
            .clearAndSetSemantics { contentDescription = "$day, $time" },
        horizontalArrangement = Arrangement.spacedBy(DS.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(28.dp)
                .background(if (on) p.accentOnNight else Color.White.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            DrafftIcon(if (state == RowState.AGREED) "check" else "calendar", size = 14.dp, tint = if (on) p.onAccentOnNight else Color.White)
        }
        Column(Modifier.weight(1f)) {
            Text(day, style = TextStyles.subheadline.semibold, color = Color.White)
            Text(time, Modifier.alpha(0.7f), style = TextStyles.caption, color = Color.White)
        }
        if (canAnswer) {
            CheckDisc(isOn = state == RowState.SELECTED, ring = Color.White.copy(alpha = 0.35f))
        }
    }
}

// MARK: Status & actions

@Composable
private fun StatusPill(status: SessionProposal.Status, mine: Boolean) {
    val (text, icon) = when (status) {
        // No name in the pill: a pill stays on one line, and names are never truncated.
        SessionProposal.Status.PENDING -> (if (mine) L("Waiting") else L("New invite")) to "hourglass"
        SessionProposal.Status.ACCEPTED -> L("Confirmed") to "check"
        SessionProposal.Status.DECLINED -> L("Declined") to "close"
        SessionProposal.Status.COUNTERED -> L("Other times suggested") to "undo-left"
        SessionProposal.Status.CANCELLED -> L("Cancelled") to "calendar-minus"
    }
    val accepted = status == SessionProposal.Status.ACCEPTED
    val ink = if (accepted) DS.palette.night else Color.White
    AnimatedContent(
        targetState = Triple(text, icon, accepted),
        transitionSpec = { fadeIn(Motion.gentle()).togetherWith(fadeOut(Motion.gentle())) },
        label = "statusPill",
    ) { (t, i, a) ->
        Row(
            Modifier
                .background(if (a) Color.White else Color.White.copy(alpha = 0.14f), CircleShape)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(DS.Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DrafftIcon(i, size = 14.dp, tint = ink)
            Text(t, style = TextStyles.caption.bold, color = ink, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun Actions(
    session: SessionProposal,
    mine: Boolean,
    profileName: String,
    chatID: String,
    busy: Boolean,
    selected: Instant?,
    onPick: (Instant) -> Unit,
    onDecline: () -> Unit,
    onCounter: () -> Unit,
    onSafety: () -> Unit,
    onCancel: () -> Unit,
) {
    when {
        session.status == SessionProposal.Status.PENDING && !mine -> Column(verticalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
            DrafftButton(onClick = { selected?.let(onPick) }, enabled = selected != null && !busy) {
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
            DrafftButton(
                onClick = onCounter,
                modifier = Modifier.border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(DS.Radius.xl)),
                kind = DrafftButtonKind.DARK,
                enabled = !busy,
            ) {
                DrafftIcon("calendar", size = 20.dp, tint = LocalContentColor.current)
                Text(L("Other times"), maxLines = 2, textAlign = TextAlign.Center)
            }
            QuietLink(L("Not this time"), enabled = !busy, onClick = onDecline)
        }
        session.status == SessionProposal.Status.PENDING -> Column(verticalArrangement = Arrangement.spacedBy(DS.Space.xs)) {
            Text(
                L("%s will pick a time or suggest others.", profileName),
                style = TextStyles.footnote,
                color = Color.White.copy(alpha = 0.6f),
            )
            QuietLink(L("Cancel session"), enabled = !busy, onClick = onCancel)
        }
        session.status == SessionProposal.Status.ACCEPTED -> Column(verticalArrangement = Arrangement.spacedBy(DS.Space.xs)) {
            CalendarButton(session = session, partner = profileName, chatID = chatID)
            PressScaleButton(onClick = onSafety, modifier = Modifier.fillMaxWidth(), scale = 1f) {
                Row(
                    Modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp),
                    horizontalArrangement = Arrangement.spacedBy(DS.Space.xs, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DrafftIcon("shield-check", size = 18.dp, tint = Color.White.copy(alpha = 0.75f))
                    Text(
                        L("Meet safely"),
                        style = TextStyles.subheadline.semibold,
                        color = Color.White.copy(alpha = 0.75f),
                        maxLines = 2,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            QuietLink(L("Cancel session"), enabled = !busy, onClick = onCancel)
        }
        session.status == SessionProposal.Status.COUNTERED -> Text(
            L("New times below."),
            style = TextStyles.footnote,
            color = Color.White.copy(alpha = 0.6f),
        )
        else -> Unit
    }
}

/** A quiet full-width text action on the card ("Not this time", "Cancel session"). */
@Composable
private fun QuietLink(text: String, enabled: Boolean, onClick: () -> Unit) {
    TextLinkButton(onClick = onClick, enabled = enabled, fullWidth = true) {
        Text(
            text,
            style = TextStyles.subheadline.semibold,
            color = if (enabled) Color.White.copy(alpha = 0.6f) else LocalContentColor.current,
            maxLines = 2,
            textAlign = TextAlign.Center,
        )
    }
}

/** "Confirm Sat 12, 9:00" for the time picked. */
private fun confirmTitle(d: Instant): String = L("Confirm %s, %s", DateText.weekdayShortDay(d), DateText.time(d))

/** "Confirm 9:00": the short form, when the day doesn't fit on the button's line. */
private fun confirmTimeTitle(d: Instant): String = L("Confirm %s", DateText.time(d))
