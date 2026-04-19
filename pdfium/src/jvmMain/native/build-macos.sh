#!/usr/bin/env bash
# Compile the PDFium JNI glue for macOS (arm64 + x86_64 variants, no universal).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
: "${PDFIUM_INCLUDE:?}"
: "${PDFIUM_LIB_ARM64:?}"
: "${PDFIUM_LIB_X64:?}"
: "${OUT_DIR_ARM64:?}"
: "${OUT_DIR_X64:?}"

java_home="${JAVA_HOME:-$(/usr/libexec/java_home)}"

build_arch() {
    local arch="$1" libdir="$2" outdir="$3"
    mkdir -p "$outdir"
    clang++ -std=c++17 -O2 -arch "$arch" -fvisibility=hidden -dynamiclib \
        -I"$PDFIUM_INCLUDE" \
        -I"$java_home/include" -I"$java_home/include/darwin" \
        -Wl,-rpath,@loader_path \
        -L"$libdir" -lpdfium \
        "$here/pdfium_jni.cpp" -o "$outdir/libpdfiumjni.dylib"
    install_name_tool -change @rpath/libpdfium.dylib @loader_path/libpdfium.dylib "$outdir/libpdfiumjni.dylib" || true
    echo "Built $outdir/libpdfiumjni.dylib ($arch)"
}

build_arch arm64  "$PDFIUM_LIB_ARM64" "$OUT_DIR_ARM64"
build_arch x86_64 "$PDFIUM_LIB_X64"   "$OUT_DIR_X64"
