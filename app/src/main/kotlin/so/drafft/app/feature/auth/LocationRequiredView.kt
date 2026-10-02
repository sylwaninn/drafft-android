package so.drafft.app.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.koin.compose.koinInject
import so.drafft.core.data.location.LocationGate
import so.drafft.core.data.platform.LocationProvider.Authorization
import so.drafft.core.data.telemetry.Screen
import so.drafft.core.model.L
import so.drafft.core.ui.TrackScreen
import so.drafft.core.ui.components.DrafftButton
import so.drafft.core.ui.components.EmptyStateArt
import so.drafft.core.ui.components.EmptyStateIllustration
import so.drafft.core.ui.platform.LocalPlatformUi
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.LocalReduceMotion
import so.drafft.core.ui.theme.NightSurface
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.branded
import so.drafft.core.ui.theme.display
import so.drafft.core.ui.theme.medium
import so.drafft.core.ui.theme.semibold

// Port of Drafft/Features/Auth/LocationRequiredView.swift.

/**
 * Blocking screen when drafft can't use the location: it shows people near you, so it can't be used
 * without it. Precise or approximate doesn't matter; "while using the app" is enough. No close button:
 * it goes away by itself once location is on, and says the one way there for the case at hand.
 */
@Composable
fun LocationRequiredView(modifier: Modifier = Modifier) {
    TrackScreen(Screen.LOCATION_REQUIRED)
    val location = koinInject<LocationGate>()
    val status by location.authorization.collectAsState()
    val servicesOff by location.servicesOff.collectAsState()
    val canPrompt by location.canPrompt.collectAsState()
    val platform = LocalPlatformUi.current
    val openSettings = platform.rememberOpenAppSettings()
    val openLocationSettings = platform.rememberOpenLocationSettings()
    val reduceMotion = LocalReduceMotion.current
    var appeared by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val p = DS.palette

    // The phone's location switch off, the permission refused, or never answered (or refused once:
    // Android still asks then): each has its own way back.
    val situation = when {
        servicesOff -> Situation.SERVICES_OFF
        status == Authorization.NOT_DETERMINED || canPrompt -> Situation.NOT_ASKED
        else -> Situation.REFUSED
    }
    val message = when (situation) {
        Situation.SERVICES_OFF -> L("Location Services are off on this iPhone. Turn them on in Settings, under Privacy & Security.")
        else -> L("drafft needs it to show people near you. While Using the App is enough, and only your area is ever shown.")
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        NightSurface {
            Column(Modifier.fillMaxSize()) {
                BoxWithConstraints(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
                ) {
                    val viewport = maxHeight
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .heightIn(min = viewport)
                            .padding(start = DS.Space.xl, end = DS.Space.xl, top = DS.Space.md, bottom = DS.Space.xl),
                        verticalArrangement = Arrangement.spacedBy(DS.Space.xl),
                    ) {
                        Spacer(Modifier.weight(1f).heightIn(min = DS.Space.lg))
                        Box(Modifier.fillMaxWidth().rise(appeared, 0, reduceMotion)) {
                            // (200 - 54) / 2: the sign sits in the middle of its 200 pt map.
                            EmptyStateIllustration(
                                EmptyStateArt(symbol = "map-point-remove"),
                                Modifier.offset(x = (-73).dp),
                                tint = p.accentOnNight,
                            )
                        }
                        Column(
                            Modifier.rise(appeared, 1, reduceMotion),
                            verticalArrangement = Arrangement.spacedBy(DS.Space.sm),
                        ) {
                            Text(
                                L("Turn location back on."),
                                Modifier.semantics { heading() },
                                style = display(40f),
                                color = p.accentOnNight,
                            )
                            Text(branded(message), style = TextStyles.body, color = Color.White.copy(alpha = 0.75f))
                        }
                        if (situation == Situation.REFUSED) Steps(Modifier.rise(appeared, 2, reduceMotion))
                    }
                }
                // Pinned at the bottom: the system prompt while it can still show, Settings otherwise.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Color.Black)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                        .padding(start = DS.Space.xl, end = DS.Space.xl, top = DS.Space.sm, bottom = DS.Space.xs)
                        .rise(appeared, 3, reduceMotion),
                ) {
                    when (situation) {
                        Situation.NOT_ASKED -> DrafftButton(onClick = { location.request() }) {
                            ButtonIcon("map-point")
                            Text(L("Allow location"), maxLines = 2)
                        }
                        Situation.SERVICES_OFF -> DrafftButton(onClick = openLocationSettings) {
                            ButtonIcon("settings")
                            Text(L("Open Settings"), maxLines = 2)
                        }
                        Situation.REFUSED -> DrafftButton(onClick = openSettings) {
                            ButtonIcon("settings")
                            Text(L("Open Settings"), maxLines = 2)
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        // Whatever was being typed gives way.
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        appeared = true
    }
}

private enum class Situation { SERVICES_OFF, REFUSED, NOT_ASKED }

/** The way back once the permission was refused: Settings opens on drafft's own page. */
@Composable
private fun Steps(modifier: Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(DS.palette.nightRaised, RoundedCornerShape(DS.Radius.xl))
            .padding(DS.Space.lg),
        verticalArrangement = Arrangement.spacedBy(DS.Space.md),
    ) {
        listOf(L("Tap Location"), L("Pick While Using the App")).forEachIndexed { i, text ->
            Row(horizontalArrangement = Arrangement.spacedBy(DS.Space.md), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(36.dp).background(Color.White.copy(alpha = 0.1f), CircleShape).clearAndSetSemantics { },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${i + 1}", style = TextStyles.subheadline.semibold, color = Color.White)
                }
                Text(branded(text), Modifier.weight(1f), style = TextStyles.subheadline.medium, color = Color.White)
            }
        }
    }
}

@Composable
private fun ButtonIcon(symbol: String) {
    DrafftIcon(symbol, size = (17f * 1.2f).dp, tint = LocalContentColor.current)
}
