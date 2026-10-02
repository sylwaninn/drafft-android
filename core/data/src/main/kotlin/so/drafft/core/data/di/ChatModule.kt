package so.drafft.core.data.di

import org.koin.dsl.module
import so.drafft.core.data.audio.AudioPlayback
import so.drafft.core.data.audio.AudioSessionController
import so.drafft.core.data.audio.VoiceRecorder
import so.drafft.core.data.chat.ChatMediaCheck

/**
 * Chat and audio, the platform-neutral half. `androidChatModule` (src/android) provides `ChatService`
 * (Stream), `AudioEngine` and `AudioFocus`.
 */
val chatModule = module {
    single { ChatMediaCheck(get()) }
    single { AudioSessionController(get()) }
    single { AudioPlayback(get(), get()) }
    // One per screen that records.
    factory { VoiceRecorder(get(), get(), get()) }
}
