# gomobile JNI surface (full flavor's mihomo core).
-keep class go.** { *; }
-keep class com.bobassist.gomobile.** { *; }

# ONNX Runtime (PP-OCRv5 engine; JNI + reflection) — keep so R8 doesn't strip the OCR path.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# ML Kit text recognition (fallback OCR).
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.mlkit.**
