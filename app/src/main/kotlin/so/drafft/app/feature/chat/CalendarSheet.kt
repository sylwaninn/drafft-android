package so.drafft.app.feature.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import so.drafft.core.data.AppModel
import so.drafft.core.data.platform.Haptics
import so.drafft.core.data.sessions.SessionCalendar
import so.drafft.core.model.L
import so.drafft.core.model.SessionProposal
import so.drafft.core.ui.LocalAppModel
import so.drafft.core.ui.components.DrafftButton
import so.drafft.core.ui.components.PressScaleButton
import so.drafft.core.ui.components.draftTrail
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.semibold

// Port of Drafft/Features/Chat/CalendarSheet.swift.
//
// The iPhone opens the system "New Event" sheet, prefilled with the session, and the person saves it
// there (`AddToCalendarSheet`). Android has no such sheet that hands the saved event back, so the event
// is written by `SessionCalendar.add` (same title, time, 90 minutes, note and reminder an hour before)
// and linked to the session at once; tapping "In your calendar" then shows it in the calendar app to
// review or edit it.

/**
 * "Add to calendar" for a confirmed session. Full access is asked first: the added event is then
 * linked to the session and follows it (`SessionCalendar`), through its `drafft://session/<id>` URL.
 * Refused access shows a banner instead. Once added, it turns into "In your calendar". [compact]:
 * round icon button (Sessions tab card).
 */
@Composable
fun CalendarButton(
    session: SessionProposal,
    partner: String,
    /** The conversation it belongs to. */
    chatID: String,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val app = LocalAppModel.current
    val calendar = koinInject<SessionCalendar>()
    val scope = rememberCoroutineScope()
    val added = session.id in app.sessionsInCalendar
    val label = if (added) L("In your calendar. Add again") else L("Add to calendar")

    fun open() {
        Haptics.tap()
        // Full access first, so the event can follow the session. Refused: nothing is added, a banner
        // says why and opens Settings.
        scope.launch {
            when (calendar.requestAccess()) {
                SessionCalendar.Access.FULL, SessionCalendar.Access.ADD_ONLY -> addOrShow(app, calendar, session, chatID, partner)
                SessionCalendar.Access.REFUSED -> {
                    Haptics.warning()
                    CalendarAccessNotice.show()
                }
            }
        }
    }

    val a11y = Modifier.semantics { contentDescription = label }
    when {
        compact -> PressScaleButton(onClick = ::open, modifier = modifier.then(a11y)) {
            AnimatedContent(
                targetState = added,
                transitionSpec = { fadeIn(Motion.snappy()).togetherWith(fadeOut(Motion.snappy())) },
                label = "calendarGlyph",
            ) { on ->
                Row(
                    Modifier
                        .size(52.dp)
                        .background(Color.White.copy(alpha = 0.14f), CircleShape),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DrafftIcon(
                        if (on) "calendar.badge.checkmark" else "calendar.badge.plus",
                        size = 20.dp,
                        tint = if (on) DS.palette.accentOnNight else Color.White,
                    )
                }
            }
        }
        added -> PressScaleButton(onClick = ::open, modifier = modifier.fillMaxWidth().then(a11y), scale = 0.97f) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 52.dp)
                    .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(DS.Radius.xl))
                    .padding(horizontal = DS.Space.lg),
                horizontalArrangement = Arrangement.spacedBy(DS.Space.sm, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DrafftIcon("calendar.badge.checkmark", size = 20.dp, tint = DS.palette.accentOnNight)
                Text(L("In your calendar"), style = TextStyles.body.semibold, color = DS.palette.accentOnNight)
            }
        }
        else -> DrafftButton(
            onClick = ::open,
            modifier = modifier
                .padding(start = 12.dp)
                .draftTrail(RoundedCornerShape(DS.Radius.xl), step = DpOffset((-6).dp, 0.dp))
                .then(a11y),
        ) {
            DrafftIcon("calendar.badge.plus", size = 20.dp, tint = LocalContentColor.current)
            Text(L("Add to calendar"), maxLines = 2)
        }
    }
}

/**
 * Adds the event (linked to the session), or, already added, shows it in the calendar app to review.
 * An event the person deleted since is added again.
 */
private suspend fun addOrShow(
    app: AppModel,
    calendar: SessionCalendar,
    session: SessionProposal,
    chatID: String,
    partner: String,
) {
    val linked = calendar.links[session.id]?.eventID
    if (linked != null && calendar.writer.event(linked) != null) {
        calendar.writer.open(linked)
        return
    }
    calendar.add(session, chatID = chatID, partner = partner) ?: return
    Haptics.success()
    app.sessionsInCalendar = app.sessionsInCalendar + session.id
}
