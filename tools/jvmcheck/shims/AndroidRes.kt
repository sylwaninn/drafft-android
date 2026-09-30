@file:Suppress("UNUSED_PARAMETER", "unused")

package androidx.compose.ui.res

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter

@Composable
fun painterResource(id: Int): Painter = ColorPainter(Color.Gray)

fun ImageBitmap.Companion.imageResource(res: Any?, id: Int): ImageBitmap = ImageBitmap(1, 1)

@Composable
fun ImageBitmap.Companion.imageResource(id: Int): ImageBitmap = ImageBitmap(1, 1)
