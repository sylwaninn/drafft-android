package so.drafft.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.TextStyles

/**
 * A group in a sheet, with an optional small title: a sage well on the white sheet (a white block on a
 * page), like every sheet in You. Its fields stay white, so they stand out of the well.
 */
@Composable
fun SheetBlock(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(DS.palette.canvas, RoundedCornerShape(DS.Radius.xl))
            .padding(DS.Space.lg),
        verticalArrangement = Arrangement.spacedBy(DS.Space.md),
    ) {
        if (title != null) {
            Text(title, Modifier.semantics { heading() }, style = TextStyles.headline, color = DS.palette.ink)
        }
        content()
    }
}
