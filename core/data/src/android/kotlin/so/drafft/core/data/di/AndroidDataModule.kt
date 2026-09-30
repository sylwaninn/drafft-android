package so.drafft.core.data.di

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.bind
import org.koin.dsl.module
import so.drafft.core.data.backend.BackendConfig
import so.drafft.core.data.platform.AndroidAppInfo
import so.drafft.core.data.platform.AndroidLocationProvider
import so.drafft.core.data.platform.AndroidNetworkMonitor
import so.drafft.core.data.platform.AppInfo
import so.drafft.core.data.platform.AppLifecycle
import so.drafft.core.data.platform.DeviceIntegrityProvider
import so.drafft.core.data.platform.DiagnosticsSource
import so.drafft.core.data.platform.ExitReasonDiagnostics
import so.drafft.core.data.platform.KeyValueStore
import so.drafft.core.data.platform.LocationProvider
import so.drafft.core.data.platform.NetworkMonitor
import so.drafft.core.data.platform.ProcessAppLifecycle
import so.drafft.core.data.platform.SharedPreferencesKeyValueStore
import so.drafft.core.data.platform.UnsupportedDeviceIntegrity

/**
 * The Android side of [dataModule]: key-value storage, app and device info, the app's lifecycle,
 * location (the activity on screen installs [AndroidLocationProvider.requester]), the connection coming
 * back, device integrity and the exit diagnostics.
 */
val androidDataModule = module {
    single<KeyValueStore> { SharedPreferencesKeyValueStore(androidContext()) }
    single<AppInfo> { AndroidAppInfo(androidContext(), get<BackendConfig>().environment) }
    single<AppLifecycle> { ProcessAppLifecycle }
    single { AndroidLocationProvider(androidContext(), get()) } bind LocationProvider::class
    single<NetworkMonitor> { AndroidNetworkMonitor(androidContext()) }
    single<DeviceIntegrityProvider> { UnsupportedDeviceIntegrity }
    single<DiagnosticsSource> { ExitReasonDiagnostics(androidContext(), get()) }
}
