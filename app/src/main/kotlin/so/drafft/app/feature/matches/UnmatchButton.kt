package so.drafft.app.feature.matches

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import so.drafft.core.data.platform.Haptics
import so.drafft.core.model.L
import so.drafft.core.model.Profile
import so.drafft.core.ui.LocalAppModel
import so.drafft.core.ui.components.ConfirmAction
import so.drafft.core.ui.components.DrafftConfirm
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.medium

// Ports Drafft/Features/Matches/UnmatchButton.swift.

/**
 * "Unmatch" on a matched profile opened from its chat: quiet like Report or block, confirmed first.
 * The chat closes behind it ([onDone]), then the match ends (`AppModel.unmatch`).
 */
@Composable
fun UnmatchButton(profile: Profile, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val app = LocalAppModel.current
    var confirming by remember { mutableStateOf(false) }
    val p = DS.palette
    val label = L("Unmatch %s", profile.name)

    fun unmatch() {
        Haptics.success()
        val person = profile
        onDone()
        // The model's own scope: this button leaves with the chat that just closed.
        app.scope.launch {
            delay(350)
            app.unmatch(person)
        }
    }

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .defaultMinSize(minHeight = 44.dp)
                .clickable(remember { MutableInteractionSource() }, indication = null) { confirming = true }
                .clearAndSetSemantics {
                    role = Role.Button
                    contentDescription = label
                },
            contentAlignment = Alignment.Center,
        ) {
            Row(
                Modifier
                    .defaultMinSize(minHeight = 36.dp)
                    .border(1.dp, p.hairline, CircleShape)
                    .padding(horizontal = DS.Space.lg),
                horizontalArrangement = Arrangement.spacedBy(DS.Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DrafftIcon("heart-crack", size = 16.dp, tint = p.body)
                Text(L("Unmatch"), style = TextStyles.footnote.medium, color = p.body)
            }
        }
    }

    DrafftConfirm(
        visible = confirming,
        onDismissRequest = { confirming = false },
        icon = "heart-crack",
        title = L("Unmatch %s?", profile.name),
        message = L("Your chat ends for both of you, and you won't see each other in Discover again."),
        actions = listOf(ConfirmAction(L("Unmatch"), ConfirmAction.Kind.DESTRUCTIVE) { unmatch() }),
    )
}
