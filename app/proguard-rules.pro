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
