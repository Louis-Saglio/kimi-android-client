# ProGuard rules for Kimi Android client.
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions
-keep class fr.louissaglio.kimi.android.** { *; }
-dontwarn com.google.errorprone.annotations.**
