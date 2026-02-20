# 默认 ProGuard 规则
-keepattributes *Annotation*

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule {
 <init>(...);
}
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
  **[] $VALUES;
  public *;
}

# Media3
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# JNI (libass)
-keep class com.hx.nekomimi.subtitle.NativeAssRenderer { *; }
