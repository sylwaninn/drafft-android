package so.drafft.app

import android.Manifest
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.CompletableDeferred
import so.drafft.core.data.notifications.NotificationService
import so.drafft.core.data.notifications.handleNotificationTap
import so.drafft.core.data.platform.PermissionPrompter
import org.koin.android.ext.android.inject
import so.drafft.core.data.AppModel
import so.drafft.core.data.backend.BackendConfig
import so.drafft.core.data.platform.AndroidLocationProvider
import so.drafft.core.ui.platform.AndroidPlatformUi
import so.drafft.core.ui.platform.LocalPlatformUi
import so.drafft.core.ui.theme.DrafftTheme
import so.drafft.core.ui.theme.LocalReduceMotion

/**
 * The only activity. Edge to edge (content under the status and navigation bars, like the iPhone),
 * portrait, the system splash handing over to SplashView on its first frame.
 */
class MainActivity : ComponentActivity() {
    private val app: AppModel by inject()
    private val config: BackendConfig by inject()
    private val location: AndroidLocationProvider by inject()
    private val notifications: NotificationService by inject()

    /** The prompt in flight (one at a time), answered by [askPermissions]. */
    private var pendingPrompt: CompletableDeferred<Map<String, Boolean>>? = null
    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        pendingPrompt?.complete(it)
        pendingPrompt = null
    }

    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        location.onPermissionResult()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // System permissions (notifications, camera, microphone, calendar) are asked from here, the
        // activity on screen; the services only see the answer.
        PermissionPrompter.engine = PermissionPrompter.Engine { permissions -> prompt(permissions) }
        notifications.handleNotificationTap(intent)
        location.requester = {
            askLocation.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION))
        }
        location.rationale = {
            ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        val reduceMotion = runCatching {
            Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)

        setContent {
            DrafftTheme(darkTheme = isSystemInDarkTheme()) {
                CompositionLocalProvider(
                    LocalPlatformUi provides AndroidPlatformUi,
                    LocalReduceMotion provides reduceMotion,
                ) {
                    if (!config.isConfigured) {
                        MissingConfigView(config.missing)
                    } else {
                        RootView(app)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notifications.handleNotificationTap(intent)
    }

    private suspend fun prompt(permissions: List<String>): PermissionPrompter.Result {
        if (permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            return PermissionPrompter.Result.GRANTED
        }
        pendingPrompt?.cancel()
        val answer = CompletableDeferred<Map<String, Boolean>>().also { pendingPrompt = it }
        askPermissions.launch(permissions.toTypedArray())
        val granted = answer.await()
        return when {
            permissions.all { granted[it] == true } -> PermissionPrompter.Result.GRANTED
            // No rationale after a refusal: Android won't show the prompt again, only Settings can.
            permissions.none { ActivityCompat.shouldShowRequestPermissionRationale(this, it) } ->
                PermissionPrompter.Result.DENIED_FOR_GOOD
            else -> PermissionPrompter.Result.DENIED
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from Settings: permissions may have changed.
        location.refreshAuthorization()
    }

    override fun onDestroy() {
        location.requester = null
        location.rationale = null
        PermissionPrompter.engine = PermissionPrompter.Engine { PermissionPrompter.Result.DENIED }
        super.onDestroy()
    }
}
