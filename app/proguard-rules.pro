# Release shrinking (R8). Keeps what is reached only by name at run time.

# PdfBox loads fonts, encodings and filters by class name and from its own
# resource files: keep it whole.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-keep class com.tom_roush.harmony.** { *; }

# Optional parts PdfBox can use but this app does not include.
-dontwarn com.gemalto.jp2.**
-dontwarn javax.xml.**
-dontwarn org.bouncycastle.**
