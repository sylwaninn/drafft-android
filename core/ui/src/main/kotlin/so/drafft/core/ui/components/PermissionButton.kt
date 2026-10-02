package so.drafft.core.ui.components

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import so.drafft.core.data.platform.PermissionStatus
import so.drafft.core.data.platform.SystemPermission
import so.drafft.core.model.L
import so.drafft.core.ui.theme.DrafftIcon

/**
 * The action for a permission that isn't on: the system prompt the first time, then Settings once
 * refused (never a dead, disabled button). [askTitle] is already localized ("Turn on
 * notifications", "Allow location"). [kind] styles it.
 */
@Composable
fun PermissionButton(
    permission: SystemPermission,
    askTitle: String,
    symbol: String,
    modifier: Modifier = Modifier,
    kind: DrafftButtonKind = DrafftButtonKind.PRIMARY,
) {
    val refused = permission.permission == PermissionStatus.DENIED
    val scope = rememberCoroutineScope()
    DrafftButton(
        onClick = {
            if (refused) permission.openSettings() else scope.launch { permission.requestPermission() }
        },
        modifier = modifier,
        kind = kind,
    ) {
        DrafftIcon(if (refused) "settings" else symbol, size = (17f * 1.2f).dp, tint = LocalContentColor.current)
        Text(if (refused) L("Open Settings") else askTitle, maxLines = 2, overflow = TextOverflow.Clip)
    }
}
