package so.drafft.app.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import so.drafft.core.data.platform.Haptics
import so.drafft.core.model.L
import so.drafft.core.model.SessionProposal
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.semibold

/**
 * A session in the conversation, said in order of what matters: a sentence of context (who did what),
 * then a card whose disc carries the state, then the sport and when, and a caret that says "open".
 * Nothing to answer here: the session's own page (`SessionDetailView`) is one tap away. The card sits on
 * the side of whoever proposed it.
 */
@Composable
fun SessionRefCard(
    session: SessionProposal,
    /** Proposed by the person (not by [name]). */
    mine: Boolean,
    name: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = DS.palette
    val shape = RoundedCornerShape(DS.Radius.xl + 2.dp)
    // An answer to give and a confirmed session are filled, the rest soft.
    val active = when (session.status) {
        SessionProposal.Status.ACCEPTED -> true
        SessionProposal.Status.PENDING -> !mine
        else -> false
    }
    val over = session.status == SessionProposal.Status.DECLINED ||
        session.status == SessionProposal.Status.COUNTERED ||
        session.status == SessionProposal.Status.CANCELLED
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, Motion.snappy(), label = "refCardPress")
    Column(
        modifier
            // Air above, like between groups of bubbles: the sentence belongs to its card, not to the bubble before.
            .padding(top = DS.Space.md)
            .widthIn(max = 320.dp),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            caption(session.status, mine, name),
            Modifier.padding(horizontal = 6.dp),
            style = TextStyles.footnote.semibold,
            color = p.mute,
            textAlign = if (mine) TextAlign.End else TextAlign.Start,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                // A plain click, nothing else: the row owns the long press (reactions) and the slide
                // (reply), and ignores the click that ends one of them ([onOpen] is the row's). A click
                // gives way to the thread's scroll as soon as the finger moves.
                .clickable(source, indication = null, role = Role.Button) {
                    Haptics.tap()
                    onOpen()
                }
                .semantics(mergeDescendants = true) { }
                .background(p.white, shape)
                .padding(DS.Space.md),
            horizontalArrangement = Arrangement.spacedBy(DS.Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    // Confirmed is the sports' green; an answer to give is the accent; the rest sits soft.
                    .background(
                        when {
                            session.status == SessionProposal.Status.ACCEPTED -> p.like
                            active -> p.lime
                            else -> p.canvasSoft
                        },
                        CircleShape,
                    )
                    .clearAndSetSemantics { },
                contentAlignment = Alignment.Center,
            ) {
                val ink = when {
                    session.status == SessionProposal.Status.ACCEPTED -> p.onLike
                    active -> p.onLime
                    else -> p.ink
                }
                DrafftIcon(stateIcon(session.status, mine), size = 24.dp, tint = ink)
            }
            Column(
                Modifier
                    .weight(1f)
                    .alpha(if (over) 0.55f else 1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(session.sport.displayName, style = TextStyles.headline, color = p.ink)
                Text(whenText(session), style = TextStyles.subheadline, color = p.body)
            }
            Box(
                Modifier
                    .size(32.dp)
                    .background(p.canvasSoft, CircleShape)
                    .clearAndSetSemantics { },
                contentAlignment = Alignment.Center,
            ) {
                DrafftIcon("alt-arrow-right", size = 16.dp, tint = p.ink)
            }
        }
    }
}

/** The state, as the card's mark. */
private fun stateIcon(status: SessionProposal.Status, mine: Boolean): String = when (status) {
    SessionProposal.Status.PENDING -> if (mine) "hourglass" else "reply"
    SessionProposal.Status.ACCEPTED -> "check"
    SessionProposal.Status.DECLINED -> "close"
    SessionProposal.Status.COUNTERED -> "undo-left"
    SessionProposal.Status.CANCELLED -> "calendar-minus"
}

/** Its time, or how many times are offered. */
private fun whenText(session: SessionProposal): String =
    if (session.status == SessionProposal.Status.PENDING && session.options.size > 1) {
        L("%d times offered", session.options.size)
    } else {
        session.whenText
    }

/** Who did what, in a sentence. */
private fun caption(status: SessionProposal.Status, mine: Boolean, name: String): String = when (status) {
    SessionProposal.Status.PENDING -> if (mine) L("You proposed a session to %s", name) else L("%s proposes a session", name)
    SessionProposal.Status.ACCEPTED -> if (mine) L("%s confirmed your session", name) else L("You confirmed %s's session", name)
    SessionProposal.Status.DECLINED -> if (mine) L("%s declined your session", name) else L("You declined %s's session", name)
    SessionProposal.Status.COUNTERED -> if (mine) L("%s suggested other times", name) else L("You suggested other times to %s", name)
    SessionProposal.Status.CANCELLED -> L("Session cancelled")
}
