# ProGuard rules for VMflow Vending App

# Keep Kotlin serialization classes
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep model classes used with serialization
-keep,includedescriptorclasses class xyz.vmflow.vending.domain.model.**$$serializer { *; }
-keepclassmembers class xyz.vmflow.vending.domain.model.** {
    *** Companion;
}
-keepclasseswithmembers class xyz.vmflow.vending.domain.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep Ktor classes
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# Keep Kable BLE classes
-keep class com.juul.kable.** { *; }
-dontwarn com.juul.kable.**

# Keep Supabase classes
-keep class io.github.jan.supabase.** { *; }
-dontwarn io.github.jan.supabase.**

# Keep Koin
-keep class org.koin.** { *; }
