package so.drafft.app.feature.auth

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import so.drafft.core.model.L
import so.drafft.core.ui.components.ConfirmAction
import so.drafft.core.ui.components.DrafftConfirm
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.Motion
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.semibold

/**
 * Sign-up header, one row: Back, the stepper, Skip. Pinned once over the whole flow, so it never
 * slides with the steps. Skip keeps its slot when it isn't offered (only faded out), so the stepper
 * never moves; the stepper's bar sits level with the chevron, its chapter name under it.
 */
@Composable
fun OnboardingHeader(
    chapters: List<ChapterSteps>,
    step: Int,
    skippable: Boolean,
    confirmLeave: Boolean,
    onConfirmLeaveChange: (Boolean) -> Unit,
    back: () -> Unit,
    skip: () -> Unit,
    leave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = DS.palette
    val skipAlpha by animateFloatAsState(if (skippable) 1f else 0f, Motion.gentle(), label = "skip")
    // System back is the chevron: the previous step, or the leave confirmation on the first one.
    BackHandler(enabled = !confirmLeave) { if (step > 0) back() else onConfirmLeaveChange(true) }
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = DS.Space.lg, end = DS.Space.lg, bottom = DS.Space.md),
        horizontalArrangement = Arrangement.spacedBy(DS.Space.md),
        verticalAlignment = Alignment.Top,
    ) {
        // Always a way back: previous step, or out of sign-up from the first one.
        val backLabel = if (step > 0) L("Back") else L("Leave sign-up")
        Box(
            Modifier
                .size(width = 44.dp, height = ROW_HEIGHT)
                .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) {
                    if (step > 0) back() else onConfirmLeaveChange(true)
                }
                .clearAndSetSemantics { contentDescription = backLabel },
            contentAlignment = Alignment.CenterStart,
        ) {
            // `.body.weight(.semibold)`.
            DrafftIcon("alt-arrow-left", size = (17f * 1.2f).dp, tint = p.ink)
        }
        DrafftConfirm(
            visible = confirmLeave,
            onDismissRequest = { onConfirmLeaveChange(false) },
            icon = "undo-left",
            title = L("Leave sign-up?"),
            message = L("Your answers won't be kept. You'll start over next time."),
            cancelTitle = L("Keep going"),
            actions = listOf(ConfirmAction(L("Leave"), ConfirmAction.Kind.DESTRUCTIVE, leave)),
        )
        ChapterStepper(
            chapters,
            current = step,
            // Level the bar (not the whole stepper) with the chevron: the name hangs below.
            modifier = Modifier
                .weight(1f)
                .padding(top = (ROW_HEIGHT - ChapterStepperBarHeight) / 2),
        )
        Box(
            Modifier
                .size(width = 76.dp, height = ROW_HEIGHT)
                .graphicsLayer { alpha = skipAlpha }
                .clickable(remember { MutableInteractionSource() }, indication = null, enabled = skippable, role = Role.Button, onClick = skip)
                .then(if (skippable) Modifier else Modifier.clearAndSetSemantics { }),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(
                L("Skip"),
                style = TextStyles.subheadline.semibold,
                color = p.body,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

/** The buttons' 48 pt row; the stepper's bar sits on its middle. */
private val ROW_HEIGHT = 48.dp
