#!/usr/bin/env bash
# Compile the PDFium JNI glue for Linux x86_64.
# Env in: PDFIUM_INCLUDE, PDFIUM_LIB, OUT_DIR (provided by Gradle).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
: "${PDFIUM_INCLUDE:?}"
: "${PDFIUM_LIB:?}"
: "${OUT_DIR:?}"

java_home="${JAVA_HOME:-}"
if [[ -z "$java_home" ]]; then
    java_home="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
fi

mkdir -p "$OUT_DIR"
out_file="$OUT_DIR/libpdfiumjni.so"

clang++ -std=c++17 -fPIC -O2 -fvisibility=hidden -shared \
    -I"$PDFIUM_INCLUDE" \
    -I"$java_home/include" -I"$java_home/include/linux" \
    -Wl,-rpath,'$ORIGIN' \
    -L"$PDFIUM_LIB" -lpdfium \
    "$here/pdfium_jni.cpp" -o "$out_file"

echo "Built $out_file"
