# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-keep class io.github.grachevskaya.asya.zygote.ZygoteEntry { premain(); main(); }

#
# Keep this module's own classes. R8 renamed them in the first release build and the manager
# stopped opening: its activity and the instrumentation that replaces the host's are reached by
# name, so a renamed class is a class that cannot be found. The size win of minification comes
# from the code nobody calls, which this does not touch.
#
-keep class io.github.grachevskaya.asya.** { *; }
-keepclassmembers class io.github.grachevskaya.asya.** { *; }
-keepnames class io.github.grachevskaya.asya.**
-dontwarn com.v7878.r8.annotations.KeepCodeAttribute
-dontwarn com.v7878.r8.annotations.KeepCode
