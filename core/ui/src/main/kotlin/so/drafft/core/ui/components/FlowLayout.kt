package so.drafft.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import so.drafft.core.ui.theme.DS

// Port of FlowLayout (Drafft/Features/Auth/OnboardingView.swift).

/**
 * Simple wrapping layout for chips: left to right, a new row when the next chip doesn't fit, the same
 * [spacing] between chips and between rows. Each chip is measured once per pass (the sports picker has
 * 80+ chips) and keeps its own size.
 */
@Composable
fun FlowLayout(
    modifier: Modifier = Modifier,
    spacing: Dp = DS.Space.sm,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val loose = Constraints(maxWidth = constraints.maxWidth)
        val placeables = measurables.map { it.measure(loose) }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        val positions = ArrayList<Pair<Int, Int>>(placeables.size)
        var x = 0
        var y = 0
        var rowH = 0
        var maxX = 0
        for (p in placeables) {
            if (x + p.width > width && x > 0) {
                x = 0
                y += rowH + gap
                rowH = 0
            }
            positions += x to y
            x += p.width + gap
            rowH = maxOf(rowH, p.height)
            maxX = maxOf(maxX, x - gap)
        }
        val layoutWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else maxX
        layout(layoutWidth.coerceIn(constraints.minWidth, maxOf(constraints.minWidth, layoutWidth)), (y + rowH).coerceAtLeast(constraints.minHeight)) {
            placeables.forEachIndexed { i, p -> p.place(positions[i].first, positions[i].second) }
        }
    }
}
