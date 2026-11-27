# Zeta Board R8 / ProGuard rules (release build: code is shrunk, optimised and renamed).
#
# Classes named in AndroidManifest.xml (activities, services, receivers, the keyboard service) are kept by the
# Android build automatically. The app parses JSON by hand (org.json, not Gson/Moshi) and uses no other reflection,
# so nothing else needs to be kept by name.

# Crash reports (Firebase Crashlytics) stay readable: keep file names and line numbers; the mapping file that
# reverses the scrambled names is uploaded automatically by the Crashlytics Gradle plugin.
-keepattributes SourceFile,LineNumberTable,*Annotation*
-renamesourcefileattribute SourceFile

# Firebase and AndroidX ship their own rules inside their libraries; these just quiet harmless warnings about
# annotation classes those rules refer to that are not actually on the compile classpath.
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.annotations.**
