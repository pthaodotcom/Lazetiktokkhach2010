# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /usr/local/Cellar/android-sdk/24.4.1_1/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.

# Keep the service to prevent it from being stripped if not referenced directly (though it's in Manifest)
-keep class com.perkompus.laze.LazeService { *; }
