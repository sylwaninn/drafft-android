package so.drafft.app.feature.me

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import org.koin.compose.koinInject
import so.drafft.core.data.notifications.NotificationService
import so.drafft.core.data.platform.PermissionStatus
import so.drafft.core.model.Brand
import so.drafft.core.model.DateText
import so.drafft.core.model.L
import so.drafft.core.ui.LocalAppModel
import so.drafft.core.ui.components.NightBlock
import so.drafft.core.ui.components.PermissionButton
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.bold
import so.drafft.core.ui.theme.branded
import so.drafft.core.ui.theme.semibold

// Port of Drafft/Features/Me/NotificationsSettingsView.swift.

/** Settings › Notifications: the system permission first, then what to be notified about. Present it in a `DrafftSheet`. */
@Composable
fun NotificationsSettingsView(modifier: Modifier = Modifier) {
    val app = LocalAppModel.current
    val notifications = koinInject<NotificationService>()
    val scroll = rememberScrollState()
    val allowed = notifications.isAllowed

    SheetPage(L("Notifications"), scroll, modifier) { bars ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(bars)
                .padding(DS.Space.lg),
            verticalArrangement = Arrangement.spacedBy(DS.Space.md),
        ) {
            StatusBlock(notifications)
            SettingsGroup(L("Activity"), allowed) {
                ToggleRow(L("New matches"), "heart.fill", notifications.matches, { notifications.matches = it })
                Divider()
                ToggleRow(L("Likes you"), "heart.circle.fill", notifications.likes, { notifications.likes = it })
                Divider()
                ToggleRow(L("Messages"), "bubble.left.fill", notifications.messages, { notifications.messages = it })
                Divider()
                ToggleRow(
                    L("Show message previews"), "text.bubble.fill",
                    notifications.messagePreviews, { notifications.messagePreviews = it },
                    detail = L("Off: notifications only say who wrote, not what."),
                    enabled = notifications.messages,
                )
                Divider()
                ToggleRow(
                    L("Reactions"), "face.smiling.inverse",
                    notifications.reactions, { notifications.reactions = it },
                    detail = L("When someone reacts to one of your messages."),
                    enabled = notifications.messages,
                )
            }
            SettingsGroup(L("Sessions"), allowed) {
                ToggleRow(
                    L("The evening before"), "moon.fill",
                    notifications.sessionEvening, { notifications.sessionEvening = it },
                    detail = L("At %s, a reminder of tomorrow's session.", eveningTime()),
                )
                Divider()
                ToggleRow(L("An hour before"), "alarm.fill", notifications.sessionHourBefore, { notifications.sessionHourBefore = it })
            }
            // The weekly boost comes with drafft tempo: its notification only makes sense then.
            if (app.isPremium) {
                SettingsGroup(Brand.TIER_NAME, allowed) {
                    ToggleRow(
                        L("Weekly boost"), "bolt.fill",
                        notifications.weeklyBoost, { notifications.weeklyBoost = it },
                        detail = L("When your free boost of the week is added."),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBlock(notifications: NotificationService) {
    val p = DS.palette
    val allowed = notifications.isAllowed
    NightBlock(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(DS.Space.xl), verticalArrangement = Arrangement.spacedBy(DS.Space.md)) {
            Row(horizontalArrangement = Arrangement.spacedBy(DS.Space.md), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(48.dp)
                        .background(if (allowed) p.accentOnNight else Color.White.copy(alpha = 0.14f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    DrafftIcon(
                        if (allowed) "bell.badge.fill" else "bell.slash.fill",
                        size = 24.dp,
                        tint = if (allowed) p.onAccentOnNight else Color.White,
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        if (allowed) L("Notifications are on") else L("Notifications are off"),
                        style = TextStyles.headline,
                        color = Color.White,
                    )
                    Text(
                        when {
                            allowed -> L("Choose what you hear about below.")
                            notifications.isDenied -> L("Turn them on in iPhone Settings to hear about matches and sessions.")
                            else -> L("Hear about matches, messages and sessions right away.")
                        },
                        style = TextStyles.subheadline,
                        color = Color.White.copy(alpha = 0.72f),
                    )
                }
            }
            if (notifications.permission != PermissionStatus.ALLOWED) {
                PermissionButton(permission = notifications, askTitle = L("Turn on notifications"), symbol = "bell.fill")
            }
        }
    }
}

/** The section title lives inside its white block (no loose text on the sage page). */
@Composable
private fun SettingsGroup(title: String, allowed: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val p = DS.palette
    Column(
        Modifier
            .fillMaxWidth()
            .alpha(if (allowed) 1f else 0.5f)
            .background(p.canvas, RoundedCornerShape(DS.Radius.xl))
            .padding(bottom = DS.Space.xs),
    ) {
        Text(
            branded(title, brandWeight = FontWeight.ExtraBold),
            Modifier
                .padding(start = DS.Space.lg, end = DS.Space.lg, top = DS.Space.lg, bottom = DS.Space.xs)
                .semantics { heading() },
            style = TextStyles.footnote.bold,
            color = p.mute,
        )
        androidx.compose.runtime.CompositionLocalProvider(LocalGroupEnabled provides allowed) { content() }
    }
}

/** Whether the group's toggles can be used (off until notifications are allowed). */
private val LocalGroupEnabled = androidx.compose.runtime.compositionLocalOf { true }

@Composable
private fun Divider() = Hairline(start = 60.dp)

@Composable
private fun ToggleRow(
    title: String,
    icon: String,
    isOn: Boolean,
    onChange: (Boolean) -> Unit,
    detail: String? = null,
    enabled: Boolean = true,
) {
    val p = DS.palette
    val usable = enabled && LocalGroupEnabled.current
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = DS.Space.lg, vertical = DS.Space.md),
        horizontalArrangement = Arrangement.spacedBy(DS.Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).background(p.canvasSoft, CircleShape), contentAlignment = Alignment.Center) {
            DrafftIcon(icon, size = 15.6.dp, tint = p.ink)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = TextStyles.body.semibold, color = p.ink)
            if (detail != null) Text(detail, style = TextStyles.footnote, color = p.body)
        }
        DrafftSwitch(checked = isOn, onCheckedChange = onChange, enabled = usable)
    }
}

/** When the evening-before reminder goes out (20:00), in the app's time format. */
private fun eveningTime(): String =
    DateText.time(LocalDate.now(DateText.zone).atTime(20, 0).atZone(DateText.zone).toInstant())
