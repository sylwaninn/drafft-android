package so.drafft.app.feature.chat

import androidx.compose.runtime.Composable
import so.drafft.core.ui.platform.LocalPlatformUi

// The camera itself is Android's (the system camera app through `PlatformUi.rememberCameraCapture`,
// core:ui src/android).

/** What the camera took: a photo (JPEG bytes), or a video file in the app's cache. */
typealias CameraCapture = so.drafft.core.ui.platform.CameraCapture

/**
 * The system camera, photo or video (the person switches in the camera itself), full screen. Its own
 * controls: shutter, retake, "Use Photo". Videos up to 3 minutes. Returns the action that opens it, or
 * null when the phone has no camera.
 */
@Composable
fun rememberCameraPicker(onCapture: (CameraCapture) -> Unit): (() -> Unit)? =
    LocalPlatformUi.current.rememberCameraCapture(onCapture)
