/**
 * ass_jni.c - libass JNI 桥接层
 *
 * 将 libass 的 ASS 字幕渲染能力暴露给 Kotlin/Android 层。
 * 核心流程:
 *   1. init()       → 初始化 libass 库和渲染器
 *   2. loadTrack()   → 加载 ASS 字幕文本
 *   3. renderFrame() → 渲染指定时间点的字幕为 ARGB Bitmap
 *   4. destroy()     → 释放所有资源
 *
 * 参考: 优秀开源项目的 ASS_Image → QPainter 渲染方式，
 *       在此实现为 ASS_Image → Android Bitmap (ARGB_8888)
 */

#include <jni.h>
#include <android/log.h>
#include <android/bitmap.h>
#include <ass/ass.h>
#include <stdlib.h>
#include <string.h>

#define LOG_TAG "AssJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// 全局状态 (每个实例独立)
typedef struct {
    ASS_Library  *library;
    ASS_Renderer *renderer;
    ASS_Track    *track;
    int           frame_w;
    int           frame_h;
} AssContext;

// ============================================================
// 辅助函数
// ============================================================

/**
 * libass 日志回调
 */
static void ass_log_callback(int level, const char *fmt, va_list va, void *data) {
    if (level > 5) return; // 过滤过于详细的日志
    char buf[512];
    vsnprintf(buf, sizeof(buf), fmt, va);
    switch (level) {
        case 0: LOGE("libass: %s", buf); break;
        case 1:
        case 2: LOGW("libass: %s", buf); break;
        default: LOGI("libass: %s", buf); break;
    }
}

/**
 * 将 ASS_Image 链表合成到 ARGB 像素缓冲区
 *
 * libass 的 ASS_Image 结构:
 * - bitmap: 8位 alpha 遮罩
 * - color:  0xBBGGRRAA (BGR顺序, AA是反转alpha)
 * - dst_x, dst_y: 目标位置
 * - w, h: 尺寸
 * - stride: 行字节跨度
 * - next: 链表下一个
 *
 * 输出到 ARGB_8888 格式的 Android Bitmap (0xAARRGGBB)
 */
static void blend_ass_image(uint32_t *pixels, int canvas_w, int canvas_h,
                            ASS_Image *img_list) {
    for (ASS_Image *img = img_list; img != NULL; img = img->next) {
        // 解析颜色: libass 使用 RGBA 格式 (R在高位)
        // color = 0xRRGGBBAA, 其中 AA 是反转的透明度
        uint8_t r = (img->color >> 24) & 0xFF;
        uint8_t g = (img->color >> 16) & 0xFF;
        uint8_t b = (img->color >>  8) & 0xFF;
        uint8_t a = (img->color >>  0) & 0xFF; // 反转alpha: 0=不透明, 255=全透明

        for (int y = 0; y < img->h; y++) {
            int dst_y = img->dst_y + y;
            if (dst_y < 0 || dst_y >= canvas_h) continue;

            uint8_t *src_row = img->bitmap + y * img->stride;
            uint32_t *dst_row = pixels + dst_y * canvas_w;

            for (int x = 0; x < img->w; x++) {
                int dst_x = img->dst_x + x;
                if (dst_x < 0 || dst_x >= canvas_w) continue;

                // src_row[x] 是 bitmap 的 alpha 值 (0=透明, 255=不透明)
                uint8_t bitmap_alpha = src_row[x];
                if (bitmap_alpha == 0) continue;

                // 最终 alpha = bitmap_alpha * (255 - color_alpha) / 255
                uint32_t final_alpha = (uint32_t)bitmap_alpha * (255 - a) / 255;
                if (final_alpha == 0) continue;

                // 目标像素 (ARGB_8888: 0xAARRGGBB)
                uint32_t dst_pixel = dst_row[dst_x];
                uint8_t dst_a = (dst_pixel >> 24) & 0xFF;
                uint8_t dst_r = (dst_pixel >> 16) & 0xFF;
                uint8_t dst_g = (dst_pixel >>  8) & 0xFF;
                uint8_t dst_b = (dst_pixel >>  0) & 0xFF;

                // Alpha 混合 (Porter-Duff SRC_OVER)
                uint32_t inv_alpha = 255 - final_alpha;
                uint8_t out_a = (uint8_t)(final_alpha + dst_a * inv_alpha / 255);
                uint8_t out_r, out_g, out_b;

                if (out_a == 0) {
                    out_r = out_g = out_b = 0;
                } else {
                    out_r = (uint8_t)((r * final_alpha + dst_r * dst_a * inv_alpha / 255) / out_a);
                    out_g = (uint8_t)((g * final_alpha + dst_g * dst_a * inv_alpha / 255) / out_a);
                    out_b = (uint8_t)((b * final_alpha + dst_b * dst_a * inv_alpha / 255) / out_a);
                }

                dst_row[dst_x] = ((uint32_t)out_a << 24) |
                                 ((uint32_t)out_r << 16) |
                                 ((uint32_t)out_g <<  8) |
                                 ((uint32_t)out_b);
            }
        }
    }
}

/**
 * 计算 ASS_Image 链表的边界框 (用于裁剪输出)
 */
static void compute_bounding_box(ASS_Image *img_list, int canvas_w, int canvas_h,
                                  int *out_left, int *out_top,
                                  int *out_right, int *out_bottom) {
    int left   = canvas_w;
    int top    = canvas_h;
    int right  = 0;
    int bottom = 0;

    for (ASS_Image *img = img_list; img != NULL; img = img->next) {
        if (img->w <= 0 || img->h <= 0) continue;
        int x1 = img->dst_x;
        int y1 = img->dst_y;
        int x2 = img->dst_x + img->w;
        int y2 = img->dst_y + img->h;

        if (x1 < left)   left   = x1;
        if (y1 < top)    top    = y1;
        if (x2 > right)  right  = x2;
        if (y2 > bottom) bottom = y2;
    }

    // 限制在画布范围内
    *out_left   = left   < 0 ? 0 : left;
    *out_top    = top    < 0 ? 0 : top;
    *out_right  = right  > canvas_w ? canvas_w : right;
    *out_bottom = bottom > canvas_h ? canvas_h : bottom;
}

// ============================================================
// JNI 方法实现
// ============================================================

#define JNI_METHOD(return_type, method_name) \
    JNIEXPORT return_type JNICALL \
    Java_com_hx_nekomimi_subtitle_AssRenderer_##method_name

/**
 * 初始化 libass 上下文
 * @return native 指针 (long)
 */
JNI_METHOD(jlong, nativeInit)(JNIEnv *env, jobject thiz) {
    AssContext *ctx = (AssContext *)calloc(1, sizeof(AssContext));
    if (!ctx) {
        LOGE("分配 AssContext 失败");
        return 0;
    }

    ctx->library = ass_library_init();
    if (!ctx->library) {
        LOGE("ass_library_init 失败");
        free(ctx);
        return 0;
    }

    ass_set_message_cb(ctx->library, ass_log_callback, NULL);

    ctx->renderer = ass_renderer_init(ctx->library);
    if (!ctx->renderer) {
        LOGE("ass_renderer_init 失败");
        ass_library_done(ctx->library);
        free(ctx);
        return 0;
    }

    // 使用 Android 系统字体路径
    ass_set_fonts(ctx->renderer, NULL, "sans-serif",
                  ASS_FONTPROVIDER_NONE, NULL, 0);

    LOGI("libass 初始化成功");
    return (jlong)(intptr_t)ctx;
}

/**
 * 设置渲染帧尺寸
 */
JNI_METHOD(void, nativeSetFrameSize)(JNIEnv *env, jobject thiz,
                                      jlong ptr, jint width, jint height) {
    AssContext *ctx = (AssContext *)(intptr_t)ptr;
    if (!ctx || !ctx->renderer) return;

    ctx->frame_w = width;
    ctx->frame_h = height;
    ass_set_frame_size(ctx->renderer, width, height);
    LOGI("设置帧尺寸: %dx%d", width, height);
}

/**
 * 加载 ASS 字幕内容 (从字符串)
 */
JNI_METHOD(jboolean, nativeLoadTrack)(JNIEnv *env, jobject thiz,
                                       jlong ptr, jstring assContent) {
    AssContext *ctx = (AssContext *)(intptr_t)ptr;
    if (!ctx || !ctx->library) return JNI_FALSE;

    // 释放旧 track
    if (ctx->track) {
        ass_free_track(ctx->track);
        ctx->track = NULL;
    }

    const char *content = (*env)->GetStringUTFChars(env, assContent, NULL);
    if (!content) {
        LOGE("获取 ASS 内容字符串失败");
        return JNI_FALSE;
    }

    size_t len = strlen(content);
    // ass_read_memory 需要一份可写副本
    char *buf = (char *)malloc(len + 1);
    if (!buf) {
        (*env)->ReleaseStringUTFChars(env, assContent, content);
        LOGE("分配缓冲区失败");
        return JNI_FALSE;
    }
    memcpy(buf, content, len + 1);

    ctx->track = ass_read_memory(ctx->library, buf, len, NULL);
    free(buf);
    (*env)->ReleaseStringUTFChars(env, assContent, content);

    if (!ctx->track) {
        LOGE("ass_read_memory 失败");
        return JNI_FALSE;
    }

    LOGI("加载 ASS track 成功, 事件数: %d, 样式数: %d",
         ctx->track->n_events, ctx->track->n_styles);
    return JNI_TRUE;
}

/**
 * 添加字体文件到 libass (支持内嵌字体等)
 */
JNI_METHOD(void, nativeAddFont)(JNIEnv *env, jobject thiz,
                                 jlong ptr, jstring fontName, jstring fontPath) {
    AssContext *ctx = (AssContext *)(intptr_t)ptr;
    if (!ctx || !ctx->library) return;

    const char *path = (*env)->GetStringUTFChars(env, fontPath, NULL);
    const char *name = fontName ?
        (*env)->GetStringUTFChars(env, fontName, NULL) : NULL;

    // 读取字体文件到内存
    FILE *f = fopen(path, "rb");
    if (!f) {
        LOGW("打开字体文件失败: %s", path);
        (*env)->ReleaseStringUTFChars(env, fontPath, path);
        if (name) (*env)->ReleaseStringUTFChars(env, fontName, name);
        return;
    }
    fseek(f, 0, SEEK_END);
    long fsize = ftell(f);
    fseek(f, 0, SEEK_SET);

    char *font_data = (char *)malloc(fsize);
    if (!font_data) {
        fclose(f);
        (*env)->ReleaseStringUTFChars(env, fontPath, path);
        if (name) (*env)->ReleaseStringUTFChars(env, fontName, name);
        return;
    }
    fread(font_data, 1, fsize, f);
    fclose(f);

    ass_add_font(ctx->library, (char *)(name ? name : ""), font_data, fsize);
    free(font_data);

    LOGI("添加字体: %s (%ld bytes)", name ? name : path, fsize);

    (*env)->ReleaseStringUTFChars(env, fontPath, path);
    if (name) (*env)->ReleaseStringUTFChars(env, fontName, name);

    // 重新配置字体
    ass_set_fonts(ctx->renderer, NULL, "sans-serif",
                  ASS_FONTPROVIDER_AUTODETECT, NULL, 1);
}

/**
 * 渲染指定时间点的字幕帧
 *
 * @param timeMs 当前播放时间 (毫秒)
 * @return 渲染后的 Bitmap (ARGB_8888)，如果没有字幕内容则返回 null
 *         返回的 Bitmap 已裁剪到字幕内容的边界框
 *         Bitmap 的 metadata 中包含偏移信息 (通过 intArray 返回)
 */
JNI_METHOD(jobject, nativeRenderFrame)(JNIEnv *env, jobject thiz,
                                        jlong ptr, jlong timeMs,
                                        jintArray outRect) {
    AssContext *ctx = (AssContext *)(intptr_t)ptr;
    if (!ctx || !ctx->renderer || !ctx->track) return NULL;
    if (ctx->frame_w <= 0 || ctx->frame_h <= 0) return NULL;

    int change = 0;
    ASS_Image *img_list = ass_render_frame(ctx->renderer, ctx->track,
                                            timeMs, &change);
    if (!img_list) return NULL;

    // 计算边界框
    int left, top, right, bottom;
    compute_bounding_box(img_list, ctx->frame_w, ctx->frame_h,
                         &left, &top, &right, &bottom);

    int crop_w = right - left;
    int crop_h = bottom - top;
    if (crop_w <= 0 || crop_h <= 0) return NULL;

    // 分配临时像素缓冲区 (全帧)
    uint32_t *full_pixels = (uint32_t *)calloc(ctx->frame_w * ctx->frame_h,
                                                sizeof(uint32_t));
    if (!full_pixels) {
        LOGE("分配全帧缓冲区失败");
        return NULL;
    }

    // 合成所有 ASS_Image 到全帧缓冲区
    blend_ass_image(full_pixels, ctx->frame_w, ctx->frame_h, img_list);

    // 创建裁剪后的 Bitmap
    // 获取 Bitmap.Config.ARGB_8888
    jclass configClass = (*env)->FindClass(env, "android/graphics/Bitmap$Config");
    jfieldID argb8888 = (*env)->GetStaticFieldID(env, configClass,
                                                  "ARGB_8888",
                                                  "Landroid/graphics/Bitmap$Config;");
    jobject config = (*env)->GetStaticObjectField(env, configClass, argb8888);

    // 创建 Bitmap
    jclass bitmapClass = (*env)->FindClass(env, "android/graphics/Bitmap");
    jmethodID createBitmap = (*env)->GetStaticMethodID(env, bitmapClass,
        "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jobject bitmap = (*env)->CallStaticObjectMethod(env, bitmapClass,
                                                     createBitmap,
                                                     crop_w, crop_h, config);
    if (!bitmap) {
        free(full_pixels);
        LOGE("创建 Bitmap 失败");
        return NULL;
    }

    // 锁定 Bitmap 像素并复制裁剪区域
    void *bmp_pixels;
    int ret = AndroidBitmap_lockPixels(env, bitmap, &bmp_pixels);
    if (ret != 0) {
        free(full_pixels);
        LOGE("AndroidBitmap_lockPixels 失败: %d", ret);
        return NULL;
    }

    uint32_t *dst = (uint32_t *)bmp_pixels;
    for (int y = 0; y < crop_h; y++) {
        uint32_t *src_row = full_pixels + (top + y) * ctx->frame_w + left;
        uint32_t *dst_row = dst + y * crop_w;
        memcpy(dst_row, src_row, crop_w * sizeof(uint32_t));
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    free(full_pixels);

    // 输出边界框信息: [left, top, right, bottom]
    if (outRect) {
        jint rect[4] = { left, top, right, bottom };
        (*env)->SetIntArrayRegion(env, outRect, 0, 4, rect);
    }

    return bitmap;
}

/**
 * 获取帧是否有变化 (用于优化：无变化时可复用上一帧)
 */
JNI_METHOD(jboolean, nativeHasChange)(JNIEnv *env, jobject thiz,
                                       jlong ptr, jlong timeMs) {
    AssContext *ctx = (AssContext *)(intptr_t)ptr;
    if (!ctx || !ctx->renderer || !ctx->track) return JNI_FALSE;

    int change = 0;
    ass_render_frame(ctx->renderer, ctx->track, timeMs, &change);
    return change != 0 ? JNI_TRUE : JNI_FALSE;
}

/**
 * 销毁 libass 上下文，释放所有资源
 */
JNI_METHOD(void, nativeDestroy)(JNIEnv *env, jobject thiz, jlong ptr) {
    AssContext *ctx = (AssContext *)(intptr_t)ptr;
    if (!ctx) return;

    if (ctx->track) {
        ass_free_track(ctx->track);
        ctx->track = NULL;
    }
    if (ctx->renderer) {
        ass_renderer_done(ctx->renderer);
        ctx->renderer = NULL;
    }
    if (ctx->library) {
        ass_library_done(ctx->library);
        ctx->library = NULL;
    }
    free(ctx);
    LOGI("libass 资源已释放");
}
