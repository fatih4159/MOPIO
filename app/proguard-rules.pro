# JGit — keep all classes (many are reflectively accessed)
-keep class org.eclipse.jgit.** { *; }
-dontwarn org.eclipse.jgit.**

# usb-serial-for-android
-keep class com.hoho.android.usbserial.** { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** { volatile <fields>; }

# sora-editor
-keep class io.github.rosemoe.sora.** { *; }
-dontwarn io.github.rosemoe.sora.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
