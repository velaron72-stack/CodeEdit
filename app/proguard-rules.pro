# JNI: native code looks these up by name
-keep class app.codeedit.highlight.TmNative { *; }
-keep interface app.codeedit.highlight.GrammarProvider { *; }
-keepclasseswithmembernames class * { native <methods>; }
