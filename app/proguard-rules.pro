# Retrofit / OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keepattributes Signature
-keepattributes *Annotation*
-keep class kotlin.Metadata { *; }

# kotlinx.serialization
-keepattributes InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class com.larp.launcher.data.remote.dto.** {
    *** Companion;
}
-keep,includedescriptorclass class com.larp.launcher.data.remote.dto.** { *; }

# Hilt / Dagger
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# WebRTC
-keep class org.webrtc.** { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ByteDance shadowhook (inline hook)
-keep class com.bytedance.shadowhook.** { *; }
-dontwarn com.bytedance.shadowhook.**

# ByteDance bytehook (PLT hook)
-keep class com.bytedance.android.bytehook.** { *; }
-dontwarn com.bytedance.android.bytehook.**

# Larp hook engine
-keep class com.larp.launcher.hook.** { *; }

-keep class com.regis.boot.** { *; }
-keep class com.larp.launcher.boot.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

-keep class com.larp.launcher.boot.GameBoot { *; }
