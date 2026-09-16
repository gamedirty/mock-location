# WebView 桥接方法靠注解反射调用，名字和注解都不能被 R8 改掉
-keepattributes *Annotation*
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
