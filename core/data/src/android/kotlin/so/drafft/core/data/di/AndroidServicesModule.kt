package so.drafft.core.data.di

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.bind
import org.koin.dsl.module
import so.drafft.core.data.backend.BackendConfig
import so.drafft.core.data.media.AndroidPhotoCompressor
import so.drafft.core.data.media.AndroidVideoCompressor
import so.drafft.core.data.media.Images
import so.drafft.core.data.media.PhotoCompressor
import so.drafft.core.data.media.VideoCompressor
import so.drafft.core.data.platform.AndroidCalendarWriter
import so.drafft.core.data.platform.AndroidLocalNotifications
import so.drafft.core.data.platform.AppInfo
import so.drafft.core.data.platform.CalendarWriter
import so.drafft.core.data.platform.LocalNotifications
import so.drafft.core.data.store.RevenueCatStore
import so.drafft.core.data.store.Store
import so.drafft.core.data.verification.AndroidFaceCheck
import so.drafft.core.data.verification.FaceCheck

/**
 * The Android side of [sessionsStoreNotificationsModule]: the calendar, notifications (FCM), purchases
 * (RevenueCat, configured when first created, at launch), and the media engines (photo and video
 * compression, face detection), installed at launch into the helpers the model calls statically.
 */
@OptIn(UnstableApi::class)
val androidServicesModule = module {
    single<CalendarWriter> { AndroidCalendarWriter(androidContext(), get()) }
    single<LocalNotifications> { AndroidLocalNotifications(androidContext(), get()) }
    single(createdAtStart = true) {
        RevenueCatStore(androidContext(), get(), get<AppInfo>().isDebugBuild).also { it.configure() }
    } bind Store::class
    single(createdAtStart = true) {
        PhotoCompressor.engine = AndroidPhotoCompressor(androidContext())
        VideoCompressor.engine = AndroidVideoCompressor(androidContext())
        Images.resizesOnServer = get<BackendConfig>().mediaImageResizing
        AndroidFaceCheck(androidContext()).also { FaceCheck.engine = it }
    }
}
