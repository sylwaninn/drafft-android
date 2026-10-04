package so.drafft.app.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import so.drafft.core.data.platform.Haptics
import so.drafft.core.model.L
import so.drafft.core.model.SessionProposal
import so.drafft.core.ui.components.glass
import so.drafft.core.ui.components.pressScale
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.bold
import so.drafft.core.ui.theme.semibold

/**
 * Under the chat's header while a session needs the person or waits on the other. A glass capsule, the
 * material of the bar's own buttons: the sport first (its mark, its name), when, and on the right what it
 * means for the person: "Reply" when an answer is theirs, "Waiting" when it is the other's. One tap opens
 * the session's page. A confirmed session is settled and has no banner; with nothing pending, the chat's
 * session button is the one way to propose.
 */
@Composable
fun SessionBanner(
    session: SessionProposal,
    /** Proposed by the person: it waits on the other. */
    mine: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = DS.palette
    val capsule = RoundedCornerShape(percent = 50)
    // The one time, or how many are offered.
    val whenText = if (session.options.size > 1) L("%d times offered", session.options.size) else session.whenText
    Row(
        modifier
            .fillMaxWidth()
            .pressScale({
                Haptics.tap()
                onOpen()
            }, scale = 0.98f)
            .semantics(mergeDescendants = true) { }
            .glass(capsule)
            .defaultMinSize(minHeight = 48.dp)
            .padding(8.dp)
            .padding(end = DS.Space.xs),
        horizontalArrangement = Arrangement.spacedBy(DS.Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).background(p.lime, CircleShape), contentAlignment = Alignment.Center) {
            DrafftIcon(session.sport.symbol, size = 24.dp, tint = p.onLime)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(session.sport.displayName, style = TextStyles.callout.semibold, color = p.ink)
            Text(whenText, style = TextStyles.footnote, color = p.body)
        }
        Text(
            if (mine) L("Waiting") else L("Reply"),
            Modifier
                .background(if (mine) p.ink.copy(alpha = 0.07f) else p.lime, capsule)
                .padding(horizontal = 14.dp, vertical = 7.dp),
            style = TextStyles.footnote.bold,
            color = if (mine) p.body else p.onLime,
            maxLines = 1,
            softWrap = false,
        )
    }
}
