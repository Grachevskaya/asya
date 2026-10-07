# Enum class
-keepclassmembers,allowoptimization enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keep,allowoptimization class * extends androidx.preference.PreferenceFragmentCompat

#
# Keep this module's own classes. The manager's activity is started by class name from the host
# process, so R8 renaming or removing it is exactly what stopped the manager from opening: the
# release build of this module had no keep rule for it at all. Minification still removes the
# code nobody calls.
#
-keep class io.github.grachevskaya.asya.** { *; }
-keepclassmembers class io.github.grachevskaya.asya.** { *; }
-keepnames class io.github.grachevskaya.asya.**
-keepclassmembers class io.github.grachevskaya.asya.databinding.**  {
    public <methods>;
}
