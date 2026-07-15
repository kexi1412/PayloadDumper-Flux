# Keep protobuf generated classes (reflection-free lite still needs some members)
-keep class chromeos_update_engine.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Apache Commons Compress + tukaani xz (only xz/bzip2 code paths used)
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-keep class org.tukaani.xz.** { *; }

# aircompressor zstd decoder uses sun.misc.Unsafe reflection
-keep class io.airlift.compress.** { *; }
-dontwarn io.airlift.compress.**
-dontwarn sun.misc.Unsafe
