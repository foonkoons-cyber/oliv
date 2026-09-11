# Release builds do not minify (isMinifyEnabled = false), so these rules only
# matter if that is ever turned on. Room's generated classes and OkHttp's
# optional platform hooks are the two things that break first.
-keep class com.khabar.reader.data.** { *; }
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
