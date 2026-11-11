# Keep the methods the Rail Link website calls (video mode, pull-to-refresh).
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

