#!/bin/bash
# ============================================================
# libass Android 依赖编译脚本
# 参考: mpv-android (autotools) + libass-cmake (CMake)
#
# 编译 FreeType (CMake) + FriBidi (autotools) + HarfBuzz (CMake) + libass (autotools)
#
# 使用方法:
#   export ANDROID_NDK_HOME=/path/to/ndk
#   bash build_deps.sh
#
# 前置条件: cmake, ninja, pkg-config, autoconf, automake, libtool
# ============================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_DIR="${SCRIPT_DIR}/build_deps"
PREFIX_BASE="${SCRIPT_DIR}/prebuilt"

# 依赖版本
FREETYPE_VERSION="2.13.3"
FRIBIDI_VERSION="1.0.16"
HARFBUZZ_VERSION="10.1.0"
LIBASS_VERSION="0.17.3"

# 目标架构
ABIS=("arm64-v8a" "armeabi-v7a" "x86_64")

# 编译线程数
CORES=$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)

# ============================================================
# NDK 检测和工具链设置
# ============================================================
if [ -z "$ANDROID_NDK_HOME" ]; then
    if [ -d "$HOME/Android/Sdk/ndk" ]; then
        ANDROID_NDK_HOME=$(ls -d "$HOME/Android/Sdk/ndk"/*/ 2>/dev/null | sort -V | tail -1)
        ANDROID_NDK_HOME="${ANDROID_NDK_HOME%/}"
    fi
    if [ -z "$ANDROID_NDK_HOME" ]; then
        echo "错误: 请设置 ANDROID_NDK_HOME 环境变量"
        exit 1
    fi
fi
echo "NDK 路径: $ANDROID_NDK_HOME"

# 查找 NDK 工具链
TOOLCHAIN="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64"
if [ ! -d "$TOOLCHAIN" ]; then
    TOOLCHAIN="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/darwin-x86_64"
fi
if [ ! -d "$TOOLCHAIN" ]; then
    echo "错误: 找不到 NDK 工具链"
    exit 1
fi

# 查找 NDK 自带的 CMake toolchain 文件
NDK_CMAKE_TOOLCHAIN="${ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake"
if [ ! -f "$NDK_CMAKE_TOOLCHAIN" ]; then
    echo "错误: 找不到 NDK CMake toolchain 文件: ${NDK_CMAKE_TOOLCHAIN}"
    exit 1
fi

API_LEVEL=26

# ============================================================
# 架构映射函数
# ============================================================

# autotools 的 host triple
get_autotools_host() {
    case $1 in
        arm64-v8a)   echo "aarch64-linux-android" ;;
        armeabi-v7a) echo "armv7a-linux-androideabi" ;;
        x86_64)      echo "x86_64-linux-android" ;;
    esac
}

# NDK clang 的 target triple (用于 CC/CXX)
get_clang_target() {
    case $1 in
        arm64-v8a)   echo "aarch64-linux-android${API_LEVEL}" ;;
        armeabi-v7a) echo "armv7a-linux-androideabi${API_LEVEL}" ;;
        x86_64)      echo "x86_64-linux-android${API_LEVEL}" ;;
    esac
}

# ============================================================
# 下载源码
# ============================================================
download_sources() {
    mkdir -p "${BUILD_DIR}/sources"
    cd "${BUILD_DIR}/sources"

    if [ ! -f "freetype-${FREETYPE_VERSION}.tar.xz" ]; then
        echo "下载 FreeType ${FREETYPE_VERSION}..."
        curl -LO "https://download.savannah.gnu.org/releases/freetype/freetype-${FREETYPE_VERSION}.tar.xz"
    fi

    if [ ! -f "fribidi-${FRIBIDI_VERSION}.tar.xz" ]; then
        echo "下载 FriBidi ${FRIBIDI_VERSION}..."
        curl -LO "https://github.com/fribidi/fribidi/releases/download/v${FRIBIDI_VERSION}/fribidi-${FRIBIDI_VERSION}.tar.xz"
    fi

    if [ ! -f "harfbuzz-${HARFBUZZ_VERSION}.tar.xz" ]; then
        echo "下载 HarfBuzz ${HARFBUZZ_VERSION}..."
        curl -LO "https://github.com/harfbuzz/harfbuzz/releases/download/${HARFBUZZ_VERSION}/harfbuzz-${HARFBUZZ_VERSION}.tar.xz"
    fi

    if [ ! -f "libass-${LIBASS_VERSION}.tar.xz" ]; then
        echo "下载 libass ${LIBASS_VERSION}..."
        curl -LO "https://github.com/libass/libass/releases/download/${LIBASS_VERSION}/libass-${LIBASS_VERSION}.tar.xz"
    fi
}

# ============================================================
# 设置 autotools 交叉编译环境变量
# (参考 mpv-android + libass-cmake)
# ============================================================
setup_autotools_env() {
    local ABI=$1
    local CLANG_TARGET=$(get_clang_target $ABI)

    PREFIX="${PREFIX_BASE}/${ABI}"
    mkdir -p "$PREFIX"

    export CC="${TOOLCHAIN}/bin/clang --target=${CLANG_TARGET}"
    export CXX="${TOOLCHAIN}/bin/clang++ --target=${CLANG_TARGET}"
    export AR="${TOOLCHAIN}/bin/llvm-ar"
    export RANLIB="${TOOLCHAIN}/bin/llvm-ranlib"
    export STRIP="${TOOLCHAIN}/bin/llvm-strip"
    export NM="${TOOLCHAIN}/bin/llvm-nm"
    export OBJDUMP="${TOOLCHAIN}/bin/llvm-objdump"

    export PKG_CONFIG_PATH="${PREFIX}/lib/pkgconfig"
    export PKG_CONFIG_LIBDIR="${PREFIX}/lib/pkgconfig"
    export PKG_CONFIG_SYSROOT_DIR=""
    export CFLAGS="-O2 -fPIC -I${PREFIX}/include"
    export CXXFLAGS="-O2 -fPIC -I${PREFIX}/include"
    export LDFLAGS="-L${PREFIX}/lib"
}

# ============================================================
# 编译 FreeType (使用 CMake + NDK toolchain)
# 参考: libass-cmake/cmake/freetype.cmake
# ============================================================
build_freetype() {
    local ABI=$1
    echo "===== 编译 FreeType ${FREETYPE_VERSION} (${ABI}) [CMake] ====="

    local SRC="${BUILD_DIR}/freetype-${FREETYPE_VERSION}-${ABI}"
    rm -rf "$SRC"
    cd "${BUILD_DIR}/sources"
    tar xf "freetype-${FREETYPE_VERSION}.tar.xz" -C "${BUILD_DIR}"
    mv "${BUILD_DIR}/freetype-${FREETYPE_VERSION}" "$SRC"

    mkdir -p "$SRC/_build"
    cd "$SRC/_build"

    cmake .. \
        -DCMAKE_TOOLCHAIN_FILE="${NDK_CMAKE_TOOLCHAIN}" \
        -DANDROID_ABI="${ABI}" \
        -DANDROID_PLATFORM="android-${API_LEVEL}" \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_INSTALL_PREFIX="${PREFIX}" \
        -DBUILD_SHARED_LIBS=OFF \
        -DFT_DISABLE_HARFBUZZ=ON \
        -DFT_DISABLE_BZIP2=ON \
        -DFT_DISABLE_PNG=ON \
        -DFT_DISABLE_BROTLI=ON \
        -DFT_DISABLE_ZLIB=OFF \
        -DDISABLE_FORCE_DEBUG_POSTFIX=ON \
        -G Ninja

    ninja -j${CORES}
    ninja install
    echo "✅ FreeType (${ABI}) 完成"
}

# ============================================================
# 编译 FriBidi (使用 autotools configure/make)
# 参考: libass-cmake/cmake/fribidi.cmake + mpv-android
# ============================================================
build_fribidi() {
    local ABI=$1
    local HOST=$(get_autotools_host $ABI)
    echo "===== 编译 FriBidi ${FRIBIDI_VERSION} (${ABI}) [autotools] ====="

    local SRC="${BUILD_DIR}/fribidi-${FRIBIDI_VERSION}-${ABI}"
    rm -rf "$SRC"
    cd "${BUILD_DIR}/sources"
    tar xf "fribidi-${FRIBIDI_VERSION}.tar.xz" -C "${BUILD_DIR}"
    mv "${BUILD_DIR}/fribidi-${FRIBIDI_VERSION}" "$SRC"

    mkdir -p "$SRC/_build"
    cd "$SRC/_build"

    ../configure \
        --host="${HOST}" \
        --prefix="${PREFIX}" \
        --enable-static \
        --disable-shared \
        --with-pic

    make -j${CORES}
    make install
    echo "✅ FriBidi (${ABI}) 完成"
}

# ============================================================
# 编译 HarfBuzz (使用 CMake + NDK toolchain)
# 参考: libass-cmake/cmake/harfbuzz.cmake
# ============================================================
build_harfbuzz() {
    local ABI=$1
    echo "===== 编译 HarfBuzz ${HARFBUZZ_VERSION} (${ABI}) [CMake] ====="

    local SRC="${BUILD_DIR}/harfbuzz-${HARFBUZZ_VERSION}-${ABI}"
    rm -rf "$SRC"
    cd "${BUILD_DIR}/sources"
    tar xf "harfbuzz-${HARFBUZZ_VERSION}.tar.xz" -C "${BUILD_DIR}"
    mv "${BUILD_DIR}/harfbuzz-${HARFBUZZ_VERSION}" "$SRC"

    mkdir -p "$SRC/_build"
    cd "$SRC/_build"

    # 需要让 HarfBuzz 找到 FreeType (显式指定路径, 避免 NDK toolchain 覆盖 CMAKE_PREFIX_PATH)
    cmake .. \
        -DCMAKE_TOOLCHAIN_FILE="${NDK_CMAKE_TOOLCHAIN}" \
        -DANDROID_ABI="${ABI}" \
        -DANDROID_PLATFORM="android-${API_LEVEL}" \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_INSTALL_PREFIX="${PREFIX}" \
        -DCMAKE_PREFIX_PATH="${PREFIX}" \
        -DCMAKE_FIND_ROOT_PATH="${PREFIX}" \
        -DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=BOTH \
        -DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=BOTH \
        -DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=BOTH \
        -DFREETYPE_LIBRARY="${PREFIX}/lib/libfreetype.a" \
        -DFREETYPE_INCLUDE_DIRS="${PREFIX}/include/freetype2" \
        -DBUILD_SHARED_LIBS=OFF \
        -DHB_HAVE_FREETYPE=ON \
        -DHB_HAVE_GLIB=OFF \
        -DHB_HAVE_GOBJECT=OFF \
        -DHB_HAVE_CAIRO=OFF \
        -DHB_HAVE_ICU=OFF \
        -DHB_BUILD_TESTS=OFF \
        -G Ninja

    ninja -j${CORES}
    ninja install
    echo "✅ HarfBuzz (${ABI}) 完成"
}

# ============================================================
# 编译 libass (使用 autotools configure/make)
# 关键: --disable-require-system-font-provider
# 参考: mpv-android/buildscripts/scripts/libass.sh
# ============================================================
build_libass() {
    local ABI=$1
    local HOST=$(get_autotools_host $ABI)
    echo "===== 编译 libass ${LIBASS_VERSION} (${ABI}) [autotools] ====="

    local SRC="${BUILD_DIR}/libass-${LIBASS_VERSION}-${ABI}"
    rm -rf "$SRC"
    cd "${BUILD_DIR}/sources"
    tar xf "libass-${LIBASS_VERSION}.tar.xz" -C "${BUILD_DIR}"
    mv "${BUILD_DIR}/libass-${LIBASS_VERSION}" "$SRC"

    mkdir -p "$SRC/_build"
    cd "$SRC/_build"

    # 确保 pkg-config 能找到 freetype2, fribidi, harfbuzz
    export PKG_CONFIG_PATH="${PREFIX}/lib/pkgconfig"

    ../configure \
        --host="${HOST}" \
        --prefix="${PREFIX}" \
        --enable-static \
        --disable-shared \
        --with-pic \
        --disable-fontconfig \
        --disable-require-system-font-provider \
        --disable-asm

    make -j${CORES}
    make install
    echo "✅ libass (${ABI}) 完成"
}

# ============================================================
# 主流程
# ============================================================
echo "==========================================="
echo "  libass Android 依赖编译"
echo "  (参考 mpv-android + libass-cmake)"
echo "==========================================="
echo "  FreeType  ${FREETYPE_VERSION} → CMake"
echo "  FriBidi   ${FRIBIDI_VERSION} → autotools"
echo "  HarfBuzz  ${HARFBUZZ_VERSION} → CMake"
echo "  libass    ${LIBASS_VERSION} → autotools"
echo "==========================================="

mkdir -p "${BUILD_DIR}"

echo ">>> 下载源码..."
download_sources

for ABI in "${ABIS[@]}"; do
    echo ""
    echo "==========================================="
    echo "  编译目标架构: ${ABI}"
    echo "==========================================="

    setup_autotools_env $ABI
    build_freetype $ABI
    build_fribidi $ABI
    build_harfbuzz $ABI
    build_libass $ABI

    echo "✅ ${ABI} 全部完成"
done

echo ""
echo "==========================================="
echo "  全部编译完成！"
echo "  输出目录: ${PREFIX_BASE}"
echo "==========================================="
ls -la "${PREFIX_BASE}/"
for ABI in "${ABIS[@]}"; do
    echo ""
    echo "--- ${ABI} ---"
    ls -la "${PREFIX_BASE}/${ABI}/lib/"*.a 2>/dev/null || echo "  (无 .a 文件)"
done

echo ""
echo "==========================================="
echo "  全部编译完成！"
echo "  输出目录: ${PREFIX_BASE}"
echo "==========================================="
ls -la "${PREFIX_BASE}/"
