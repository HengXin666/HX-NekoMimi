cmake_minimum_required(VERSION 3.22.1)
project("assrenderer" C)

# Android log 库
find_library(log-lib log)

# jnigraphics 库 (操作 Bitmap)
find_library(jnigraphics-lib jnigraphics)

# 预编译依赖的根目录
set(PREBUILT_DIR "${CMAKE_SOURCE_DIR}/prebuilt/${ANDROID_ABI}")

# ---- libass (静态库) ----
add_library(ass STATIC IMPORTED)
set_target_properties(ass PROPERTIES
    IMPORTED_LOCATION "${PREBUILT_DIR}/lib/libass.a"
    INTERFACE_INCLUDE_DIRECTORIES "${PREBUILT_DIR}/include"
)

# ---- FreeType (静态库) ----
add_library(freetype STATIC IMPORTED)
set_target_properties(freetype PROPERTIES
    IMPORTED_LOCATION "${PREBUILT_DIR}/lib/libfreetype.a"
    INTERFACE_INCLUDE_DIRECTORIES "${PREBUILT_DIR}/include/freetype2"
)

# ---- FriBidi (静态库) ----
add_library(fribidi STATIC IMPORTED)
set_target_properties(fribidi PROPERTIES
    IMPORTED_LOCATION "${PREBUILT_DIR}/lib/libfribidi.a"
    INTERFACE_INCLUDE_DIRECTORIES "${PREBUILT_DIR}/include"
)

# ---- HarfBuzz (静态库) ----
add_library(harfbuzz STATIC IMPORTED)
set_target_properties(harfbuzz PROPERTIES
    IMPORTED_LOCATION "${PREBUILT_DIR}/lib/libharfbuzz.a"
    INTERFACE_INCLUDE_DIRECTORIES "${PREBUILT_DIR}/include/harfbuzz"
)

# ---- 主 JNI 库 ----
add_library(assrenderer SHARED
    ass_jni.c
)

target_include_directories(assrenderer PRIVATE
    "${PREBUILT_DIR}/include"
    "${PREBUILT_DIR}/include/ass"
    "${PREBUILT_DIR}/include/freetype2"
)

# 链接顺序很重要: libass -> harfbuzz -> freetype -> fribidi
target_link_libraries(assrenderer
    ass
    harfbuzz
    freetype
    fribidi
    ${log-lib}
    ${jnigraphics-lib}
    m  # 数学库
    z  # zlib (NDK 自带)
)
