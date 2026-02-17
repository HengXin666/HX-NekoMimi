# NekoMimi ProGuard Rules

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.lifecycle.HiltViewModelFactory

# Media3
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# 保留数据类
-keep class com.hx.nekomimi.data.db.entity.** { *; }
-keep class com.hx.nekomimi.subtitle.model.** { *; }

# libass JNI 渲染器 (native 方法不能被混淆)
-keep class com.hx.nekomimi.subtitle.AssRenderer { *; }
-keepclassmembers class com.hx.nekomimi.subtitle.AssRenderer {
    native <methods>;
    public <methods>;
}
