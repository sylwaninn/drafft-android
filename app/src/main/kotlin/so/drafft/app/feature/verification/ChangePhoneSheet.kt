package so.drafft.app.feature.verification

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.koin.compose.getKoin
import org.koin.core.parameter.parametersOf
import so.drafft.app.feature.me.AccountSheet
import so.drafft.core.data.telemetry.Screen
import so.drafft.core.data.verification.PhoneVerificationModel
import so.drafft.core.model.L
import so.drafft.core.ui.LocalAppModel
import so.drafft.core.ui.components.DrafftSheet
import so.drafft.core.ui.components.LocalSheetDismiss
import so.drafft.core.ui.components.SheetBlock
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.monospacedDigits
import so.drafft.core.ui.theme.semibold

// Ports Drafft/Features/Verification/ChangePhoneSheet.swift.

/**
 * Change the phone number: verify the new one first. The number can't be removed, only replaced.
 * Shown inside a `DrafftSheet` (closed through [LocalSheetDismiss]).
 */
@Composable
fun ChangePhoneSheet(modifier: Modifier = Modifier) {
    val app = LocalAppModel.current
    val dismiss = LocalSheetDismiss.current
    val scope = rememberCoroutineScope()
    val koin = getKoin()
    val model = remember(scope) { koin.get<PhoneVerificationModel> { parametersOf(scope) } }
    var showHelp by remember { mutableStateOf(false) }
    val p = DS.palette

    LaunchedEffect(model) { model.currentNumber = app.phoneNumber?.filter { it.isDigit() || it == '+' } }
    LaunchedEffect(model.stage) {
        if (model.stage == PhoneVerificationModel.Stage.VERIFIED) app.phoneNumber = model.displayNumber
    }

    AccountSheet(
        title = L("Phone number"),
        screen = Screen.PHONE_VERIFICATION,
        actionTitle = if (model.stage == PhoneVerificationModel.Stage.VERIFIED) L("Done") else model.primaryTitle,
        enabled = model.primaryEnabled,
        loading = model.busy,
        error = if (model.stage == PhoneVerificationModel.Stage.ENTER_NUMBER && model.isSameAsCurrent) L("That's already your number.") else null,
        finished = model.stage == PhoneVerificationModel.Stage.VERIFIED,
        action = {
            when (model.stage) {
                PhoneVerificationModel.Stage.ENTER_NUMBER -> scope.launch { model.sendCode() }
                PhoneVerificationModel.Stage.ENTER_CODE -> scope.launch { model.verify() }
                PhoneVerificationModel.Stage.VERIFIED -> dismiss()
                PhoneVerificationModel.Stage.LOCKED -> showHelp = true
            }
        },
        modifier = modifier,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(p.canvas, RoundedCornerShape(DS.Radius.xl))
                .padding(DS.Space.lg),
            verticalArrangement = Arrangement.spacedBy(DS.Space.xs),
        ) {
            Text(L("Current number"), style = TextStyles.subheadline.semibold, color = p.body)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    app.phoneNumber ?: L("None"),
                    style = TextStyles.body.semibold.monospacedDigits,
                    color = p.ink,
                )
                Spacer(Modifier.weight(1f))
                // Only a number that exists can be verified.
                if (app.phoneNumber != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(DS.Space.xs), verticalAlignment = Alignment.CenterVertically) {
                        DrafftIcon("verified-check", size = 16.dp, tint = p.positiveDeep)
                        Text(L("Verified"), style = TextStyles.footnote.semibold, color = p.positiveDeep, maxLines = 1, softWrap = false)
                    }
                }
            }
            Text(
                L("You can replace your number, not remove it: it keeps your account secure."),
                Modifier.padding(top = DS.Space.xs),
                style = TextStyles.footnote,
                color = p.mute,
            )
        }

        SheetBlock { PhoneVerificationView(model) }
    }

    DrafftSheet(visible = showHelp, onDismissRequest = { showHelp = false }) {
        SupportSheet(topic = L("Phone verification"))
    }
}
