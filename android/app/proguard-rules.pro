# Proguard rules for QB Player
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
-keep public class com.naqeeb.qbplayer.MainActivity
