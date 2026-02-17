#!/bin/bash
# ============================================================
# libass Android 依赖编译脚本
# 编译 FreeType、FriBidi、HarfBuzz、libass 为 Android 静态库
#
# 使用方法:
#   export ANDROID_NDK_HOME=/path/to/ndk
#   bash build_deps.sh
#
# 前置条件: autotools, meson, ninja, pkg-config, nasm
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

# 检查 NDK
if [ -z "$ANDROID_NDK_HOME" ]; then
    # 尝试自动查找
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

TOOLCHAIN="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64"
if [ ! -d "$TOOLCHAIN" ]; then
    TOOLCHAIN="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/darwin-x86_64"
fi
if [ ! -d "$TOOLCHAIN" ]; then
    echo "错误: 找不到 NDK 工具链"
    exit 1
fi

API_LEVEL=26

# 架构映射
get_triple() {
    case $1 in
        arm64-v8a)   echo "aarch64-linux-android" ;;
        armeabi-v7a) echo "armv7a-linux-androideabi" ;;
        x86_64)      echo "x86_64-linux-android" ;;
    esac
}

get_arch() {
    case $1 in
        arm64-v8a)   echo "aarch64" ;;
        armeabi-v7a) echo "arm" ;;
        x86_64)      echo "x86_64" ;;
    esac
}

get_meson_cpu_family() {
    case $1 in
        arm64-v8a)   echo "aarch64" ;;
        armeabi-v7a) echo "arm" ;;
        x86_64)      echo "x86_64" ;;
    esac
}

# 下载源码
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

# 设置交叉编译环境
setup_env() {
    local ABI=$1
    local TRIPLE=$(get_triple $ABI)

    export CC="${TOOLCHAIN}/bin/${TRIPLE}${API_LEVEL}-clang"
    export CXX="${TOOLCHAIN}/bin/${TRIPLE}${API_LEVEL}-clang++"
    export AR="${TOOLCHAIN}/bin/llvm-ar"
    export AS="${TOOLCHAIN}/bin/llvm-as"
    export LD="${TOOLCHAIN}/bin/ld"
    export RANLIB="${TOOLCHAIN}/bin/llvm-ranlib"
    export STRIP="${TOOLCHAIN}/bin/llvm-strip"
    export NM="${TOOLCHAIN}/bin/llvm-nm"

    # armeabi-v7a 特殊处理
    if [ "$ABI" = "armeabi-v7a" ]; then
        export CC="${TOOLCHAIN}/bin/armv7a-linux-androideabi${API_LEVEL}-clang"
        export CXX="${TOOLCHAIN}/bin/armv7a-linux-androideabi${API_LEVEL}-clang++"
    fi

    PREFIX="${PREFIX_BASE}/${ABI}"
    mkdir -p "$PREFIX"

    export PKG_CONFIG_PATH="${PREFIX}/lib/pkgconfig:${PREFIX}/lib64/pkgconfig"
    export PKG_CONFIG_SYSROOT_DIR=""
    export CFLAGS="-O2 -fPIC -I${PREFIX}/include -I${PREFIX}/include/freetype2"
    export CXXFLAGS="-O2 -fPIC -I${PREFIX}/include -I${PREFIX}/include/freetype2"
    export LDFLAGS="-L${PREFIX}/lib"
}

# 生成 Meson 交叉编译文件
create_meson_cross_file() {
    local ABI=$1
    local TRIPLE=$(get_triple $ABI)
    local CPU_FAMILY=$(get_meson_cpu_family $ABI)
    local CROSS_FILE="${BUILD_DIR}/meson-${ABI}.txt"

    local CC_PATH="${TOOLCHAIN}/bin/${TRIPLE}${API_LEVEL}-clang"
    local CXX_PATH="${TOOLCHAIN}/bin/${TRIPLE}${API_LEVEL}-clang++"
    if [ "$ABI" = "armeabi-v7a" ]; then
        CC_PATH="${TOOLCHAIN}/bin/armv7a-linux-androideabi${API_LEVEL}-clang"
        CXX_PATH="${TOOLCHAIN}/bin/armv7a-linux-androideabi${API_LEVEL}-clang++"
    fi

    cat > "$CROSS_FILE" << EOF
[binaries]
c = '${CC_PATH}'
cpp = '${CXX_PATH}'
ar = '${TOOLCHAIN}/bin/llvm-ar'
strip = '${TOOLCHAIN}/bin/llvm-strip'
ranlib = '${TOOLCHAIN}/bin/llvm-ranlib'
pkgconfig = 'pkg-config'

[properties]
pkg_config_libdir = '${PREFIX_BASE}/${ABI}/lib/pkgconfig'

[host_machine]
system = 'android'
cpu_family = '${CPU_FAMILY}'
cpu = '${CPU_FAMILY}'
endian = 'little'

[built-in options]
c_args = ['-O2', '-fPIC', '-I${PREFIX_BASE}/${ABI}/include', '-I${PREFIX_BASE}/${ABI}/include/freetype2']
cpp_args = ['-O2', '-fPIC', '-I${PREFIX_BASE}/${ABI}/include', '-I${PREFIX_BASE}/${ABI}/include/freetype2']
c_link_args = ['-L${PREFIX_BASE}/${ABI}/lib']
cpp_link_args = ['-L${PREFIX_BASE}/${ABI}/lib']
default_library = 'static'
prefix = '${PREFIX_BASE}/${ABI}'
EOF
    echo "$CROSS_FILE"
}

# 编译 FreeType
build_freetype() {
    local ABI=$1
    echo "===== 编译 FreeType (${ABI}) ====="

    local SRC="${BUILD_DIR}/freetype-${FREETYPE_VERSION}-${ABI}"
    rm -rf "$SRC"
    cd "${BUILD_DIR}/sources"
    tar xf "freetype-${FREETYPE_VERSION}.tar.xz" -C "${BUILD_DIR}"
    mv "${BUILD_DIR}/freetype-${FREETYPE_VERSION}" "$SRC"
    cd "$SRC"

    local CROSS_FILE=$(create_meson_cross_file $ABI)
    meson setup builddir \
        --cross-file="$CROSS_FILE" \
        --default-library=static \
        --prefix="${PREFIX}" \
        -Dharfbuzz=disabled \
        -Dbzip2=disabled \
        -Dpng=disabled \
        -Dzlib=disabled \
        -Dbrotli=disabled
    ninja -C builddir
    ninja -C builddir install
}

# 编译 FriBidi
build_fribidi() {
    local ABI=$1
    echo "===== 编译 FriBidi (${ABI}) ====="

    local SRC="${BUILD_DIR}/fribidi-${FRIBIDI_VERSION}-${ABI}"
    rm -rf "$SRC"
    cd "${BUILD_DIR}/sources"
    tar xf "fribidi-${FRIBIDI_VERSION}.tar.xz" -C "${BUILD_DIR}"
    mv "${BUILD_DIR}/fribidi-${FRIBIDI_VERSION}" "$SRC"
    cd "$SRC"

    local CROSS_FILE=$(create_meson_cross_file $ABI)
    meson setup builddir \
        --cross-file="$CROSS_FILE" \
        --default-library=static \
        --prefix="${PREFIX}" \
        -Ddocs=false \
        -Dbin=false \
        -Dtests=false
    ninja -C builddir
    ninja -C builddir install
}

# 编译 HarfBuzz
build_harfbuzz() {
    local ABI=$1
    echo "===== 编译 HarfBuzz (${ABI}) ====="

    local SRC="${BUILD_DIR}/harfbuzz-${HARFBUZZ_VERSION}-${ABI}"
    rm -rf "$SRC"
    cd "${BUILD_DIR}/sources"
    tar xf "harfbuzz-${HARFBUZZ_VERSION}.tar.xz" -C "${BUILD_DIR}"
    mv "${BUILD_DIR}/harfbuzz-${HARFBUZZ_VERSION}" "$SRC"
    cd "$SRC"

    local CROSS_FILE=$(create_meson_cross_file $ABI)
    meson setup builddir \
        --cross-file="$CROSS_FILE" \
        --default-library=static \
        --prefix="${PREFIX}" \
        -Dfreetype=enabled \
        -Dglib=disabled \
        -Dgobject=disabled \
        -Dcairo=disabled \
        -Dicu=disabled \
        -Dtests=disabled \
        -Ddocs=disabled
    ninja -C builddir
    ninja -C builddir install
}

# 编译 libass
build_libass() {
    local ABI=$1
    echo "===== 编译 libass (${ABI}) ====="

    local SRC="${BUILD_DIR}/libass-${LIBASS_VERSION}-${ABI}"
    rm -rf "$SRC"
    cd "${BUILD_DIR}/sources"
    tar xf "libass-${LIBASS_VERSION}.tar.xz" -C "${BUILD_DIR}"
    mv "${BUILD_DIR}/libass-${LIBASS_VERSION}" "$SRC"
    cd "$SRC"

    local CROSS_FILE=$(create_meson_cross_file $ABI)
    meson setup builddir \
        --cross-file="$CROSS_FILE" \
        --default-library=static \
        --prefix="${PREFIX}" \
        -Dfontconfig=disabled \
        -Ddirectwrite=disabled \
        -Dcoretext=disabled \
        -Dasm=disabled \
        -Drequire-system-font-provider=false
    ninja -C builddir
    ninja -C builddir install
}

# 主流程
echo "==========================================="
echo "  libass Android 依赖编译"
echo "==========================================="

mkdir -p "${BUILD_DIR}"

echo ">>> 下载源码..."
download_sources

for ABI in "${ABIS[@]}"; do
    echo ""
    echo "==========================================="
    echo "  编译目标架构: ${ABI}"
    echo "==========================================="

    setup_env $ABI
    build_freetype $ABI
    build_fribidi $ABI
    build_harfbuzz $ABI
    build_libass $ABI

    echo "✅ ${ABI} 编译完成"
done

echo ""
echo "==========================================="
echo "  全部编译完成！"
echo "  输出目录: ${PREFIX_BASE}"
echo "==========================================="
ls -la "${PREFIX_BASE}/"
