# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep Room entities
-keep class com.viswa2k.syncpad.data.entity.** { *; }
-keep class com.viswa2k.syncpad.data.model.** { *; }
-keep class com.viswa2k.syncpad.data.dao.** { *; }

# Keep Hilt generated classes
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ComponentSupplier { *; }

# Keep Gson serialization classes
-keep class com.viswa2k.syncpad.sync.BlogDto { *; }
-keepclassmembers class com.viswa2k.syncpad.sync.BlogDto { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class sun.misc.Unsafe { *; }

# Keep data classes used by Supabase API
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
