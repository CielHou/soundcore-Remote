# Keep Gson serialized model classes (fields are accessed via reflection).
-keep class cn.utae.audcore.remote.data.** { *; }
-keepattributes *Annotation*,InnerClasses
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
