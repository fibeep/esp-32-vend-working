# Default ProGuard rules for ESP32 Setup app
-keepattributes *Annotation*
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class xyz.vmflow.setup.data.** { *; }
