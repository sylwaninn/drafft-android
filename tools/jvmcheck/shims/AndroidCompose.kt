// Stand-ins for Android-only Compose APIs, so the shared sources type-check on the JVM.
// Signatures follow the Android artifacts; bodies are never run.
@file:Suppress("UNUSED_PARAMETER", "unused")

package androidx.compose.ui.text.font

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
fun Font(resId: Int, weight: FontWeight = FontWeight.Normal, style: FontStyle = FontStyle.Normal): Font =
    androidx.compose.ui.text.platform.SystemFont("shim-$resId", weight, style)
