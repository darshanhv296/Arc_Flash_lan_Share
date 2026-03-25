# OkHttp ProGuard Rules
-keepattributes Signature
-keepattributes AnnotationDefault
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# Ktor ProGuard Rules
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-keep class kotlinx.coroutines.** { *; }
-keepattributes *Annotation*
-keepattributes InnerClasses

# ML Kit ProGuard Rules
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode.** { *; }
-dontwarn com.google.mlkit.**

# ZXing ProGuard Rules
-keep class com.google.zxing.** { *; }

# Compose ProGuard Rules
-keep class androidx.compose.** { *; }

# Kotlin Serialization (if used by Ktor)
-keep class kotlinx.serialization.** { *; }

# Ensure MainActivity and its ViewModel are kept
-keep class com.example.filesharing.MainActivity { *; }
-keep class com.example.filesharing.FileSharingViewModel { *; }
-keep class com.example.filesharing.TransferItem { *; }
-keep class com.example.filesharing.RemoteFile { *; }
-keep class com.example.filesharing.TransferStats { *; }
-keep class com.example.filesharing.DiscoveredDevice { *; }
