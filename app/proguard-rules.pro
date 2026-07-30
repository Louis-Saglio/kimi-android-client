# App-specific R8/ProGuard rules, applied on top of the default
# proguard-android-optimize.txt ruleset (wired in app/build.gradle.kts).
#
# kotlinx.serialization needs no keep rules here: the app only uses generated
# serializers (the @Serializable plugin's static serializer() entry points,
# which R8 seeds directly from the call sites), never reflection- or
# name-based serializer lookup, so shrinking already keeps everything needed.
#
# Add future app-specific keep rules below as they become necessary.

# Crashlytics (BoM 34.x) references android.os.ProfilingTrigger*, only present
# in android.jar from API 36 while compileSdk is 35. The call site is guarded
# by a runtime SDK check, so the classes are never touched on older devices.
-dontwarn android.os.ProfilingTrigger
-dontwarn android.os.ProfilingTrigger$Builder
