# Keep native methods and Bridge class
-keepclassmembers class com.pocketshrink.app.Bridge {
    public static native <methods>;
}
-keep class com.pocketshrink.app.Bridge { *; }
