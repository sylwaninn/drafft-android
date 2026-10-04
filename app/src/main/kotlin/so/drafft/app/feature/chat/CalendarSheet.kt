package so.drafft.app.feature.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.koin.compose.koinInject
import so.drafft.core.data.AppModel
import so.drafft.core.data.platform.Haptics
import so.drafft.core.data.sessions.SessionCalendar
import so.drafft.core.model.L
import so.drafft.core.model.SessionProposal
import so.drafft.core.ui.LocalAppModel
import so.drafft.core.ui.components.DrafftButton
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.Motion
import kotlinx.coroutines.launch

// Android has no system "New Event" sheet that hands the saved event back, so the event
// is written by `SessionCalendar.add` (the session's title and time, 90 minutes long, a note and a reminder
// an hour before)
// and linked to the session at once; tapping "In your calendar" then shows it in the calendar app to
// review or edit it.

/**
 * "Add to calendar" for a confirmed session, the pinned action of its page. Full access is asked first:
 * the added event is then linked to the session and follows it (`SessionCalendar`), through its
 * `drafft://session/<id>` URL. Refused access shows a banner instead. Once added, it turns into "In your
 * calendar", which shows the event in the calendar app.
 */
@Composable
fun CalendarButton(
    session: SessionProposal,
    partner: String,
    /** The conversation it belongs to. */
    chatID: String,
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

    DrafftButton(onClick = ::open, modifier = modifier.semantics { contentDescription = label }) {
        AnimatedContent(
            targetState = added,
            transitionSpec = { fadeIn(Motion.snappy()).togetherWith(fadeOut(Motion.snappy())) },
            label = "calendarButton",
        ) { on ->
            Row(horizontalArrangement = Arrangement.spacedBy(DS.Space.sm), verticalAlignment = Alignment.CenterVertically) {
                DrafftIcon(if (on) "calendar-check" else "calendar-add", size = 20.dp, tint = LocalContentColor.current)
                Text(if (on) L("In your calendar") else L("Add to calendar"), maxLines = 2)
            }
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
