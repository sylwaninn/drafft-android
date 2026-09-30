@file:Suppress("UNUSED_PARAMETER", "unused")

package androidx.compose.ui.res

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@Composable
fun painterResource(id: Int): Painter = ColorPainter(Color.Gray)

fun ImageBitmap.Companion.imageResource(res: Any?, id: Int): ImageBitmap = ImageBitmap(1, 1)

@Composable
fun ImageBitmap.Companion.imageResource(id: Int): ImageBitmap = ImageBitmap(1, 1)

@Composable
fun ImageVector.Companion.vectorResource(id: Int): ImageVector =
    ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).build()
