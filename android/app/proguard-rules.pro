# PdfBox-Android: isteğe bağlı JPEG2000 desteği yok
-dontwarn com.gemalto.jp2.**
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-keep class com.tom_roush.harmony.** { *; }
# LAME (jump3r) ve javax.sound yer tutucuları
-keep class de.sciss.jump3r.** { *; }
-keep class javax.sound.sampled.** { *; }
# WebView -> PDF köprüsü
-keep class android.print.PdfPrint { *; }
-keep class android.print.PdfPrint$* { *; }
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
