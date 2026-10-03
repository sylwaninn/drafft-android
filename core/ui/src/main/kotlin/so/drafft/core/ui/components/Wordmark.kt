package so.drafft.core.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.em
import so.drafft.core.model.Brand
import so.drafft.core.ui.theme.DS
import so.drafft.core.ui.theme.display

/** The logo: "drafft" set in the display face, solid. */
@Composable
fun Wordmark(
    modifier: Modifier = Modifier,
    size: Float = 28f,
    color: Color = DS.palette.lime,
) {
    Text(
        Brand.NAME,
        modifier.clearAndSetSemantics { contentDescription = Brand.NAME },
        color = color,
        style = display(size).copy(letterSpacing = (-0.02).em),
        maxLines = 1,
        softWrap = false,
    )
}
