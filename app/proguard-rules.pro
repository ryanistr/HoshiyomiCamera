# Stargazer Camera — keeps for JNI and Compose.

# Rust core entry points are called from Java by name; keep the loader.
-keep class com.hoshiyomi.camera.native.** { *; }

# Compose.
-dontwarn androidx.compose.**

# Kotlin metadata.
-keep class kotlin.Metadata { *; }
