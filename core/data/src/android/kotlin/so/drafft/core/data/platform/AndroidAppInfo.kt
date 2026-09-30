package so.drafft.core.data.platform

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import java.io.File

/** [AppInfo] from the package and the device. [environment] is the build flavor's (`ENVIRONMENT`). */
class AndroidAppInfo(private val context: Context, override val environment: String) : AppInfo {
    private val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)

    override val version: String get() = packageInfo.versionName ?: ""

    override val build: String get() = packageInfo.longVersionCode.toString()

    override val isDebugBuild: Boolean
        get() = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    override val model: String get() = Build.MODEL

    override val osVersion: String get() = Build.VERSION.RELEASE

    /**
     * The app's id on this device (`ANDROID_ID`: per app signing key, user and device). It stays
     * across reinstalls, which is what the safety checks need; a factory reset changes it.
     */
    @get:SuppressLint("HardwareIds")
    override val installID: String?
        get() = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

    override val filesDir: File get() = context.filesDir
    override val noBackupDir: File get() = context.noBackupFilesDir
    override val cacheDir: File get() = context.cacheDir
}

/** Whether the app is on screen (at least one activity resumed). Read on the main thread. */
object ProcessAppLifecycle : AppLifecycle {
    override fun isActive(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
}
