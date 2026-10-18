# Keep JNI entry points in the inference engine.
-keep class com.arm.aichat.** { *; }

# PDF text extraction (PDFBox) mentions an optional JPEG 2000 decoder that is not needed to read text.
-dontwarn com.gemalto.jp2.**
