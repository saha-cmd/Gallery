# ==============================================================================
# Gallery App - ProGuard / R8 Full Mode Optimization Rules
# ==============================================================================

# Preserve line numbers and source file attributes for actionable crash stack traces
-keepattributes SourceFile,LineNumberTable,InnerClasses,EnclosingMethod

# ------------------------------------------------------------------------------
# 1. Kotlin & Coroutines Internals
# ------------------------------------------------------------------------------
-keep class kotlin.Metadata { *; }
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { *; }

# ------------------------------------------------------------------------------
# 2. UCrop Image Cropping
# ------------------------------------------------------------------------------
-keep class com.yalantis.ucrop** { *; }
-keep interface com.yalantis.ucrop** { *; }
-dontwarn com.yalantis.ucrop**

# ------------------------------------------------------------------------------
# 3. Room Database, Entities, and DAOs
# ------------------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep class com.gallery.core.data.db.** { *; }
-dontwarn androidx.room.paging.**

# ------------------------------------------------------------------------------
# 4. Hilt Dependency Injection & Lifecycle ViewModels
# ------------------------------------------------------------------------------
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keep class * extends dagger.hilt.android.internal.managers.* { *; }
-dontwarn dagger.hilt.**

# ------------------------------------------------------------------------------
# 5. Media3 ExoPlayer
# ------------------------------------------------------------------------------
-keep class androidx.media3.extractor.** { *; }
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ------------------------------------------------------------------------------
# 6. Coil Image Loading & Video Frame Decoders
# ------------------------------------------------------------------------------
-keep class coil.** { *; }
-dontwarn coil.**

# ------------------------------------------------------------------------------
# 7. Jetpack Compose
# ------------------------------------------------------------------------------
-keep class androidx.compose.** { *; }

# ------------------------------------------------------------------------------
# 8. Suppress Harmless R8 Missing-Annotation Warnings (Tink / Security-Crypto / Guava)
# ------------------------------------------------------------------------------
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn com.google.crypto.tink.**
-dontwarn com.google.common.**

# ------------------------------------------------------------------------------
# 9. Release Log Stripping
# Strip verbose and debug logs in release; keep warnings and errors
# ------------------------------------------------------------------------------
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

# ------------------------------------------------------------------------------
# 10. Jetpack DataStore Preferences
# ------------------------------------------------------------------------------
-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite {
    <fields>;
}
-dontwarn androidx.datastore.**

# ------------------------------------------------------------------------------
# 11. AndroidX FileProvider
# ------------------------------------------------------------------------------
-keep class androidx.core.content.FileProvider { *; }
