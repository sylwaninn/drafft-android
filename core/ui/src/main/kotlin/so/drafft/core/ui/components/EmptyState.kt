package so.drafft.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import so.drafft.core.model.L
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.DrafftIcon
import so.drafft.core.ui.theme.TextStyles
import so.drafft.core.ui.theme.display

// Port of Drafft/DesignSystem/EmptyState.swift.

/**
 * An empty tab: its sign as a sticker on the page ([EmptyStateSticker]), a title, one line of
 * text, and an action if there is one to take. Sits in the middle of the space it is given.
 * [title] and [message] are already localized (through the catalog).
 */
@Composable
fun EmptyStateView(
    art: EmptyStateArt,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = DS.Space.lg),
        verticalArrangement = Arrangement.spacedBy(DS.Space.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(DS.Space.md), horizontalAlignment = Alignment.CenterHorizontally) {
            EmptyStateSticker(art)
            Column(verticalArrangement = Arrangement.spacedBy(DS.Space.sm), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    title,
                    Modifier.semantics { heading() },
                    style = display(22f),
                    color = DS.palette.ink,
                    textAlign = TextAlign.Center,
                )
                Text(message, style = TextStyles.body, color = DS.palette.body, textAlign = TextAlign.Center)
            }
        }
        actions()
    }
}

/**
 * An empty tab whose first read failed: what didn't load and a way to try again, never "nobody yet".
 * [offline]: the read never reached the server (else the server failed: no connection advice).
 */
@Composable
fun ListLoadFailureView(
    art: EmptyStateArt,
    title: String,
    offline: Boolean,
    retry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    EmptyStateView(
        art = art,
        title = title,
        message = if (offline) L("Check your connection and try again.") else L("Something went wrong. Try again in a moment."),
        modifier = modifier,
    ) {
        DrafftButton(onClick = retry, fullWidth = false) {
            DrafftIcon("refresh", size = (TextStyles.body.fontSize.value * 1.2f).dp, tint = LocalContentColor.current)
            Text(L("Try again"), maxLines = 2, overflow = TextOverflow.Clip)
        }
    }
}

/** What each empty tab draws: its tab-bar icon. */
@Immutable
data class EmptyStateArt(
    /** The sign: its outline for [EmptyStateIllustration], its bold twin ("<name>-bold") on the sticker. */
    val symbol: String,
) {
    companion object {
        val discover = EmptyStateArt("fire")
        val likes = EmptyStateArt("heart")
        val sessions = EmptyStateArt("stopwatch-play")
        val chats = EmptyStateArt("dialog-2")
    }
}

/** The sign in reserve: a thin accent outline, nothing else around it. */
@Composable
fun EmptyStateIllustration(
    art: EmptyStateArt,
    modifier: Modifier = Modifier,
    /** The outline. The accent, but for a closed account (negative). */
    tint: Color = DS.palette.lime,
) {
    Box(modifier.size(width = 200.dp, height = 64.dp), contentAlignment = Alignment.Center) {
        DrafftIcon(art.symbol, size = 62.dp, tint = tint)
    }
}
