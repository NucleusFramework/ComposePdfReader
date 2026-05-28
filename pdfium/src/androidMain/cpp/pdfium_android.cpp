// Android-specific PDFium JNI glue. Implements nRenderPageToBitmap, which writes
// PDFium's BGRA output directly into the Android Bitmap's pixel memory (locked via
// AndroidBitmap_lockPixels from the NDK). Zero intermediate copies.

#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>
#include <cstdint>
#include <cstring>

#include "fpdfview.h"
#include "fpdf_formfill.h"

#define LOG_TAG "pdfiumjni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_pdfium_jvm_PdfiumBridge_nRenderPageToBitmap(
        JNIEnv* env, jclass, jlong page, jlong form, jobject bitmap,
        jint width, jint height, jint flags) {
    if (page == 0 || bitmap == nullptr) return JNI_FALSE;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("AndroidBitmap_getInfo failed");
        return JNI_FALSE;
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("bitmap format not RGBA_8888 (got %d)", info.format);
        return JNI_FALSE;
    }

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("AndroidBitmap_lockPixels failed");
        return JNI_FALSE;
    }

    const int stride = static_cast<int>(info.stride);
    std::memset(pixels, 0xFF, static_cast<size_t>(stride) * static_cast<size_t>(height));

    // Android ARGB_8888 storage is R,G,B,A in memory → feed PDFium BGRA + reverse byte order.
    // Caller's [flags] must include FPDF_REVERSE_BYTE_ORDER (we OR it in defensively here).
    FPDF_BITMAP bmp = FPDFBitmap_CreateEx(width, height, FPDFBitmap_BGRA, pixels, stride);
    if (!bmp) {
        AndroidBitmap_unlockPixels(env, bitmap);
        return JNI_FALSE;
    }
    FPDFBitmap_FillRect(bmp, 0, 0, width, height, 0xFFFFFFFF);
    const int renderFlags = flags | FPDF_REVERSE_BYTE_ORDER;
    FPDF_PAGE p = reinterpret_cast<FPDF_PAGE>(page);
    FPDF_RenderPageBitmap(bmp, p, 0, 0, width, height, 0, renderFlags);
    if (form != 0) {
        FPDF_FORMHANDLE fh = reinterpret_cast<FPDF_FORMHANDLE>(form);
        FORM_OnAfterLoadPage(p, fh);
        FPDF_FFLDraw(fh, bmp, p, 0, 0, width, height, 0, renderFlags);
        FORM_OnBeforeClosePage(p, fh);
    }
    FPDFBitmap_Destroy(bmp);

    AndroidBitmap_unlockPixels(env, bitmap);
    return JNI_TRUE;
}

} // extern "C"
