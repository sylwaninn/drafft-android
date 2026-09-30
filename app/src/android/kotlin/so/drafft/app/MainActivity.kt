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
import androidx.compose.runtime.key
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
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
        location.requester = {
            askLocation.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION))
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
                        // The whole tree redraws in a newly picked language at once.
                        key(app.language) { RootView(app) }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from Settings: permissions may have changed.
        location.refreshAuthorization()
    }

    override fun onDestroy() {
        location.requester = null
        super.onDestroy()
    }
}
