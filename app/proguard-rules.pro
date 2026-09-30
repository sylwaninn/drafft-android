# kotlinx.serialization: keep generated serializers of the app's own @Serializable types.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers @kotlinx.serialization.Serializable class so.drafft.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class so.drafft.**$$serializer { *; }
# Stream Chat and RevenueCat ship their own consumer rules.

# Stream's logger was built against kotlinx-datetime 0.6 (Clock.System, kotlinx.datetime.Instant), which
# 0.7 (pulled by supabase-kt) moved to kotlin.time. It's only reached when Stream logging is on, and the
# app sets ChatLogLevel.NOTHING.
-dontwarn kotlinx.datetime.Clock$System
-dontwarn kotlinx.datetime.Clock
-dontwarn kotlinx.datetime.Instant
