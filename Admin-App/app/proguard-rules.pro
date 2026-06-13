# Add project specific ProGuard rules here.
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.abuzahra.admin.data.model.** { *; }
-keep class com.abuzahra.admin.data.api.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-keepclassmembers class * {
    ** Companion;
}