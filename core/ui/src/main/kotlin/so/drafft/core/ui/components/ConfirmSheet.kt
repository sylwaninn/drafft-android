package so.drafft.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import so.drafft.core.data.platform.Haptics
import so.drafft.core.model.L
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.display
import so.drafft.core.ui.theme.semibold

/** One button of a [DrafftConfirm]. */
data class ConfirmAction(
    val title: String,
    val kind: Kind = Kind.PRIMARY,
    val action: () -> Unit,
) {
    enum class Kind { PRIMARY, DESTRUCTIVE }

    val id: String get() = title
}

/**
 * `.drafftConfirm`: drafft's own confirmation, instead of the system alert or action sheet. A short
 * raised sheet sized to its content, with the brand's type, an icon disc (red when an action is
 * destructive, the accent otherwise), a clear consequence and full-width buttons, then a plain
 * Cancel. Used for log out, leave sign-up, discard changes, report and block.
 */
@Composable
fun DrafftConfirm(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    icon: String? = null,
    title: String,
    message: String? = null,
    cancelTitle: String = L("Cancel"),
    actions: List<ConfirmAction>,
    modifier: Modifier = Modifier,
) {
    DrafftSheet(
        visible = visible,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        detent = SheetDetent.FIT,
        raised = true,
    ) {
        ConfirmSheet(icon, title, message, actions, cancelTitle)
    }
}

@Composable
private fun ConfirmSheet(
    icon: String?,
    title: String,
    message: String?,
    actions: List<ConfirmAction>,
    cancelTitle: String,
) {
    val p = DS.palette
    val close = LocalSheetDismiss.current
    val destructive = actions.any { it.kind == ConfirmAction.Kind.DESTRUCTIVE }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = DS.Space.xl, end = DS.Space.xl, top = DS.Space.xxl, bottom = DS.Space.sm),
        verticalArrangement = Arrangement.spacedBy(DS.Space.xl),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(DS.Space.md)) {
            if (icon != null) {
                Box(
                    Modifier
                        .size(52.dp)
                        .background(if (destructive) p.negative else p.lime, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    DrafftIcon(icon, size = symbolBox(20f), tint = if (destructive) Color.White else p.onLime)
                }
            }
            Text(title, Modifier.semantics { heading() }, style = display(30f), color = p.ink)
            if (message != null) Text(message, style = TextStyles.body, color = p.body)
        }
        Column(verticalArrangement = Arrangement.spacedBy(DS.Space.sm)) {
            actions.forEach { a ->
                val danger = a.kind == ConfirmAction.Kind.DESTRUCTIVE
                PressScaleButton(
                    onClick = {
                        close()
                        if (danger) Haptics.warning() else Haptics.tap()
                        a.action()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 52.dp)
                        .background(if (danger) p.negative else p.lime, RoundedCornerShape(DS.Radius.xl)),
                    scale = 0.97f,
                ) {
                    Text(
                        a.title,
                        Modifier.padding(horizontal = DS.Space.lg, vertical = DS.Space.xs),
                        style = TextStyles.body.semibold,
                        color = if (danger) Color.White else p.onLime,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                }
            }
            // The whole full-width row takes the tap, not just the word.
            PressScaleButton(
                onClick = {
                    Haptics.tap()
                    close()
                },
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp),
                scale = 0.97f,
            ) {
                Text(cancelTitle, style = TextStyles.body.semibold, color = p.ink, textAlign = TextAlign.Center, maxLines = 2)
            }
        }
    }
}
