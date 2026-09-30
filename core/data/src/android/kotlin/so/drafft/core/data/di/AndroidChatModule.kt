package so.drafft.core.data.di

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import so.drafft.core.data.audio.AndroidAudioEngine
import so.drafft.core.data.audio.AndroidAudioFocus
import so.drafft.core.data.audio.AudioEngine
import so.drafft.core.data.audio.AudioFocus
import so.drafft.core.data.chat.ChatService
import so.drafft.core.data.chat.StreamChatService

/** Chat on Stream and audio on MediaPlayer/MediaRecorder; load with [chatModule]. */
val androidChatModule = module {
    single<ChatService> { StreamChatService(androidContext(), get(), get(), get(), get()) }
    single<AudioEngine> { AndroidAudioEngine(androidContext()) }
    single<AudioFocus> { AndroidAudioFocus(androidContext()) }
}
