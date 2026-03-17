# Pirate App ProGuard Rules

# Keep Compose
-dontwarn androidx.compose.**
-keep class androidx.compose.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# Bouncy Castle
-dontwarn org.bouncycastle.**
-keep class org.bouncycastle.** { *; }

# Web3j
-dontwarn org.web3j.**
-keep class org.web3j.** { *; }
-keep class org.web3j.crypto.** { *; }
-keep class org.web3j.abi.** { *; }

# XMTP
-dontwarn org.xmtp.**
-keep class org.xmtp.** { *; }

# Coil
-dontwarn coil.**
-keep class coil.** { *; }

# Agora
-dontwarn io.agora.**
-keep class io.agora.** { *; }

# Media3/ExoPlayer
-dontwarn androidx.media3.**
-keep class androidx.media3.** { *; }

# Glance
-dontwarn androidx.glance.**
-keep class androidx.glance.** { *; }

# Keep data classes used for serialization
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Keep enum classes
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Optional dependency reference from identity connector libraries.
-dontwarn groovy.lang.GroovyShell
