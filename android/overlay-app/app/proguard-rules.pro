# Phase 0: minify disabled, but keep gomobile JNI surface in case of future R8 use.
-keep class go.** { *; }
-keep class com.bobassist.gomobile.** { *; }
