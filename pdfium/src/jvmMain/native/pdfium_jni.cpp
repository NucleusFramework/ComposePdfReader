 // JNI glue for PDFium. Shared between JVM desktop (compiled per-OS by build-*.sh/.bat)
// and Android (compiled via CMake — see src/androidMain/cpp/CMakeLists.txt).
//
// All functions exposed to Kotlin follow the contract of PdfiumBridge.kt.

#include <jni.h>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <unordered_map>

#include "fpdfview.h"
#include "fpdf_doc.h"
#include "fpdf_text.h"

namespace {

std::once_flag g_initFlag;

void ensureInit() {
    std::call_once(g_initFlag, []() {
        FPDF_LIBRARY_CONFIG config{};
        config.version = 2;
        config.m_pUserFontPaths = nullptr;
        config.m_pIsolate = nullptr;
        config.m_v8EmbedderSlot = 0;
        FPDF_InitLibraryWithConfig(&config);
    });
}

// PDFium retains the raw buffer pointer passed to FPDF_LoadMemDocument64 for the document's
// lifetime — we own a copy and free it in nCloseDocument, keyed by document handle.
std::mutex g_bufMu;
std::unordered_map<FPDF_DOCUMENT, uint8_t*> g_docBuffers;

// Retrieve an FPDF meta string (UTF-16LE) as a std::string (UTF-8).
std::string readMeta(FPDF_DOCUMENT doc, const char* tag) {
    unsigned long len = FPDF_GetMetaText(doc, tag, nullptr, 0);
    if (len <= 2) return {};
    std::u16string buf(len / sizeof(char16_t), u'\0');
    FPDF_GetMetaText(doc, tag, buf.data(), len);
    // Strip trailing NUL.
    while (!buf.empty() && buf.back() == u'\0') buf.pop_back();
    // Naive UTF-16LE → UTF-8 conversion (PDF meta is ASCII-ish in practice, but handle BMP).
    std::string out;
    out.reserve(buf.size());
    for (char16_t c : buf) {
        if (c < 0x80) {
            out.push_back(static_cast<char>(c));
        } else if (c < 0x800) {
            out.push_back(static_cast<char>(0xC0 | (c >> 6)));
            out.push_back(static_cast<char>(0x80 | (c & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xE0 | (c >> 12)));
            out.push_back(static_cast<char>(0x80 | ((c >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (c & 0x3F)));
        }
    }
    return out;
}

jstring toJString(JNIEnv* env, const std::string& s) {
    if (s.empty()) return nullptr;
    return env->NewStringUTF(s.c_str());
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_pdfium_jvm_PdfiumBridge_nOpenDocument(
        JNIEnv* env, jclass, jbyteArray data, jstring password) {
    ensureInit();
    jsize size = env->GetArrayLength(data);
    auto* copy = new uint8_t[static_cast<size_t>(size)];
    env->GetByteArrayRegion(data, 0, size, reinterpret_cast<jbyte*>(copy));
    const char* pwd = nullptr;
    if (password != nullptr) pwd = env->GetStringUTFChars(password, nullptr);
    FPDF_DOCUMENT doc = FPDF_LoadMemDocument64(copy, static_cast<size_t>(size), pwd);
    if (pwd != nullptr) env->ReleaseStringUTFChars(password, pwd);
    if (!doc) {
        delete[] copy;
        return 0;
    }
    {
        std::lock_guard<std::mutex> lk(g_bufMu);
        g_docBuffers.emplace(doc, copy);
    }
    return reinterpret_cast<jlong>(doc);
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_pdfium_jvm_PdfiumBridge_nGetLastError(JNIEnv*, jclass) {
    return static_cast<jint>(FPDF_GetLastError());
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_pdfium_jvm_PdfiumBridge_nGetPageCount(JNIEnv*, jclass, jlong doc) {
    if (doc == 0) return 0;
    return FPDF_GetPageCount(reinterpret_cast<FPDF_DOCUMENT>(doc));
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_pdfium_jvm_PdfiumBridge_nGetMeta(
        JNIEnv* env, jclass, jlong doc, jstring tag) {
    if (doc == 0) return nullptr;
    const char* tagStr = env->GetStringUTFChars(tag, nullptr);
    std::string value = readMeta(reinterpret_cast<FPDF_DOCUMENT>(doc), tagStr);
    env->ReleaseStringUTFChars(tag, tagStr);
    return toJString(env, value);
}

JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_pdfium_jvm_PdfiumBridge_nLoadPage(
        JNIEnv*, jclass, jlong doc, jint index) {
    if (doc == 0) return 0;
    FPDF_PAGE page = FPDF_LoadPage(reinterpret_cast<FPDF_DOCUMENT>(doc), index);
    return reinterpret_cast<jlong>(page);
}

JNIEXPORT jfloat JNICALL
Java_dev_nucleusframework_pdfium_jvm_PdfiumBridge_nGetPageWidth(JNIEnv*, jclass, jlong page) {
    if (page == 0) return 0.f;
    return FPDF_GetPageWidthF(reinterpret_cast<FPDF_PAGE>(page));
}

JNIEXPORT jfloat JNICALL
Java_dev_nucleusframework_pdfium_jvm_PdfiumBridge_nGetPageHeight(JNIEnv*, jclass, jlong page) {
    if (page == 0) return 0.f;
    return FPDF_GetPageHeightF(reinterpret_cast<FPDF_PAGE>(page));
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_pdfium_jvm_PdfiumBridge_nClosePage(JNIEnv*, jclass, jlong page) {
    if (page == 0) return;
    FPDF_ClosePage(reinterpret_cast<FPDF_PAGE>(page));
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_pdfium_jvm_PdfiumBridge_nCloseDocument(JNIEnv*, jclass, jlong doc) {
    if (doc == 0) return;
    auto d = reinterpret_cast<FPDF_DOCUMENT>(doc);
    FPDF_CloseDocument(d);
    std::lock_guard<std::mutex> lk(g_bufMu);
    auto it = g_docBuffers.find(d);
    if (it != g_docBuffers.end()) {
        delete[] it->second;
        g_docBuffers.erase(it);
    }
}

/**
 * Render [page] into a caller-provided direct ByteBuffer. Layout is BGRA, stride = w * 4.
 * Kotlin callers can either swizzle to RGBA themselves or pass swapRedBlue=true to receive RGBA.
 */
JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_pdfium_jvm_PdfiumBridge_nRenderPage(
        JNIEnv* env, jclass, jlong page, jobject directBuffer,
        jint width, jint height, jboolean swapRedBlue) {
    if (page == 0 || directBuffer == nullptr) return JNI_FALSE;
    void* buffer = env->GetDirectBufferAddress(directBuffer);
    if (buffer == nullptr) return JNI_FALSE;
    std::memset(buffer, 0xFF, static_cast<size_t>(width) * static_cast<size_t>(height) * 4);

    const int stride = width * 4;
    FPDF_BITMAP bmp = FPDFBitmap_CreateEx(width, height, FPDFBitmap_BGRA, buffer, stride);
    if (!bmp) return JNI_FALSE;

    // White background fill (PDFium renders onto whatever the buffer already contains).
    FPDFBitmap_FillRect(bmp, 0, 0, width, height, 0xFFFFFFFF);

    int flags = FPDF_ANNOT | FPDF_LCD_TEXT;
    if (swapRedBlue) flags |= FPDF_REVERSE_BYTE_ORDER;
    FPDF_RenderPageBitmap(bmp, reinterpret_cast<FPDF_PAGE>(page),
                          0, 0, width, height, 0, flags);
    FPDFBitmap_Destroy(bmp);
    return JNI_TRUE;
}

/**
 * Render [page] directly into an externally-owned memory region at [address].
 * Layout: BGRA, stride = width*4. Caller owns the memory; we only write.
 * [flags] is a bitmask from fpdfview.h (FPDF_ANNOT, FPDF_LCD_TEXT, FPDF_REVERSE_BYTE_ORDER, …).
 * Passing 0 yields the fastest render (draft quality).
 */
JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_pdfium_jvm_PdfiumBridge_nRenderPageToAddress(
        JNIEnv*, jclass, jlong page, jlong address,
        jint width, jint height, jint flags) {
    if (page == 0 || address == 0) return JNI_FALSE;
    void* buffer = reinterpret_cast<void*>(address);
    const int stride = width * 4;
    std::memset(buffer, 0xFF, static_cast<size_t>(stride) * static_cast<size_t>(height));
    FPDF_BITMAP bmp = FPDFBitmap_CreateEx(width, height, FPDFBitmap_BGRA, buffer, stride);
    if (!bmp) return JNI_FALSE;
    FPDFBitmap_FillRect(bmp, 0, 0, width, height, 0xFFFFFFFF);
    FPDF_RenderPageBitmap(bmp, reinterpret_cast<FPDF_PAGE>(page),
                          0, 0, width, height, 0, flags);
    FPDFBitmap_Destroy(bmp);
    return JNI_TRUE;
}

/**
 * Allocate a native buffer and copy the Java byte array into it. Returns a raw pointer
 * that can be passed to [nOpenDocumentFromMemory] — the pointer must outlive every
 * document opened against it, and must eventually be freed via [nFreeBuffer].
 *
 * The pool opens N document handles against the same buffer: N× handles, 1× memory.
 */
JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_pdfium_jvm_PdfiumBridge_nAllocBuffer(
        JNIEnv* env, jclass, jbyteArray data) {
    jsize size = env->GetArrayLength(data);
    auto* buf = new uint8_t[static_cast<size_t>(size)];
    env->GetByteArrayRegion(data, 0, size, reinterpret_cast<jbyte*>(buf));
    return reinterpret_cast<jlong>(buf);
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_pdfium_jvm_PdfiumBridge_nFreeBuffer(
        JNIEnv*, jclass, jlong address) {
    if (address == 0) return;
    delete[] reinterpret_cast<uint8_t*>(address);
}

/**
 * Open a document from a pre-allocated native buffer. PDFium keeps a pointer to [address]
 * for the document's lifetime — caller must keep the memory alive until [nCloseDocument].
 * Unlike [nOpenDocument], no internal copy is made.
 */
JNIEXPORT jlong JNICALL
Java_dev_nucleusframework_pdfium_jvm_PdfiumBridge_nOpenDocumentFromMemory(
        JNIEnv* env, jclass, jlong address, jlong size, jstring password) {
    ensureInit();
    const char* pwd = nullptr;
    if (password != nullptr) pwd = env->GetStringUTFChars(password, nullptr);
    FPDF_DOCUMENT doc = FPDF_LoadMemDocument64(
        reinterpret_cast<void*>(address), static_cast<size_t>(size), pwd);
    if (pwd != nullptr) env->ReleaseStringUTFChars(password, pwd);
    return reinterpret_cast<jlong>(doc);
}

/**
 * Extract UTF-8 text from [page]. Returns null if the page has no text objects.
 */
JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_pdfium_jvm_PdfiumBridge_nGetPageText(
        JNIEnv* env, jclass, jlong page) {
    if (page == 0) return nullptr;
    FPDF_TEXTPAGE textPage = FPDFText_LoadPage(reinterpret_cast<FPDF_PAGE>(page));
    if (!textPage) return nullptr;
    int charCount = FPDFText_CountChars(textPage);
    if (charCount <= 0) {
        FPDFText_ClosePage(textPage);
        return nullptr;
    }
    // Need charCount + 1 UTF-16 units (incl. NUL terminator).
    std::u16string buf(static_cast<size_t>(charCount + 1), u'\0');
    int written = FPDFText_GetText(textPage, 0, charCount,
                                   reinterpret_cast<unsigned short*>(buf.data()));
    FPDFText_ClosePage(textPage);
    if (written <= 1) return nullptr;
    // Drop trailing NUL if present.
    while (!buf.empty() && buf.back() == u'\0') buf.pop_back();
    // UTF-16 → UTF-8.
    std::string utf8;
    utf8.reserve(buf.size());
    for (size_t i = 0; i < buf.size(); ++i) {
        char16_t c = buf[i];
        if (c < 0x80) {
            utf8.push_back(static_cast<char>(c));
        } else if (c < 0x800) {
            utf8.push_back(static_cast<char>(0xC0 | (c >> 6)));
            utf8.push_back(static_cast<char>(0x80 | (c & 0x3F)));
        } else if (c >= 0xD800 && c <= 0xDBFF && i + 1 < buf.size()) {
            // High surrogate — combine with low surrogate for astral codepoint.
            char16_t low = buf[i + 1];
            if (low >= 0xDC00 && low <= 0xDFFF) {
                uint32_t cp = 0x10000 + ((c - 0xD800u) << 10) + (low - 0xDC00u);
                utf8.push_back(static_cast<char>(0xF0 | (cp >> 18)));
                utf8.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
                utf8.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
                utf8.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
                ++i;
                continue;
            }
            utf8.push_back(static_cast<char>(0xE0 | (c >> 12)));
            utf8.push_back(static_cast<char>(0x80 | ((c >> 6) & 0x3F)));
            utf8.push_back(static_cast<char>(0x80 | (c & 0x3F)));
        } else {
            utf8.push_back(static_cast<char>(0xE0 | (c >> 12)));
            utf8.push_back(static_cast<char>(0x80 | ((c >> 6) & 0x3F)));
            utf8.push_back(static_cast<char>(0x80 | (c & 0x3F)));
        }
    }
    return env->NewStringUTF(utf8.c_str());
}

/**
 * Count of text rectangles (line-level runs) on [page]. Opens + closes a text-page
 * internally.
 */
JNIEXPORT jint JNICALL
Java_dev_nucleusframework_pdfium_jvm_PdfiumBridge_nCountTextRects(
        JNIEnv*, jclass, jlong page) {
    if (page == 0) return 0;
    FPDF_PAGE p = reinterpret_cast<FPDF_PAGE>(page);
    FPDF_TEXTPAGE tp = FPDFText_LoadPage(p);
    if (!tp) return 0;
    int n = FPDFText_CountRects(tp, 0, -1);
    FPDFText_ClosePage(tp);
    return n;
}

/**
 * Fill [outBoxes] with rect coordinates (4 floats per rect — left, bottom, right, top in
 * PDF page points) and [outTexts] with the UTF-8 text for each rect. Caller supplies arrays
 * sized for at least the count returned by [nCountTextRects]. Returns the number of rects
 * actually written (may be less if the arrays are too small).
 */
JNIEXPORT jint JNICALL
Java_dev_nucleusframework_pdfium_jvm_PdfiumBridge_nExtractTextRects(
        JNIEnv* env, jclass, jlong page,
        jfloatArray outBoxes, jobjectArray outTexts) {
    if (page == 0) return 0;
    FPDF_PAGE p = reinterpret_cast<FPDF_PAGE>(page);
    FPDF_TEXTPAGE tp = FPDFText_LoadPage(p);
    if (!tp) return 0;
    int total = FPDFText_CountRects(tp, 0, -1);
    int capacity = env->GetArrayLength(outBoxes) / 4;
    int count = (total < capacity) ? total : capacity;
    if (count == 0) {
        FPDFText_ClosePage(tp);
        return 0;
    }
    jfloat* boxes = env->GetFloatArrayElements(outBoxes, nullptr);
    for (int i = 0; i < count; i++) {
        double left = 0, top = 0, right = 0, bottom = 0;
        FPDFText_GetRect(tp, i, &left, &top, &right, &bottom);
        boxes[i * 4 + 0] = static_cast<float>(left);
        boxes[i * 4 + 1] = static_cast<float>(bottom);
        boxes[i * 4 + 2] = static_cast<float>(right);
        boxes[i * 4 + 3] = static_cast<float>(top);

        unsigned long needed = FPDFText_GetBoundedText(tp, left, top, right, bottom, nullptr, 0);
        jstring js;
        if (needed <= 1) {
            js = env->NewStringUTF("");
        } else {
            std::u16string buf(needed, u'\0');
            FPDFText_GetBoundedText(tp, left, top, right, bottom,
                                    reinterpret_cast<unsigned short*>(buf.data()), needed);
            while (!buf.empty() && buf.back() == u'\0') buf.pop_back();
            // UTF-16 → UTF-8 (BMP + surrogate pair support).
            std::string utf8;
            utf8.reserve(buf.size());
            for (size_t k = 0; k < buf.size(); ++k) {
                char16_t c = buf[k];
                if (c < 0x80) {
                    utf8.push_back(static_cast<char>(c));
                } else if (c < 0x800) {
                    utf8.push_back(static_cast<char>(0xC0 | (c >> 6)));
                    utf8.push_back(static_cast<char>(0x80 | (c & 0x3F)));
                } else if (c >= 0xD800 && c <= 0xDBFF && k + 1 < buf.size()) {
                    char16_t low = buf[k + 1];
                    if (low >= 0xDC00 && low <= 0xDFFF) {
                        uint32_t cp = 0x10000 + ((c - 0xD800u) << 10) + (low - 0xDC00u);
                        utf8.push_back(static_cast<char>(0xF0 | (cp >> 18)));
                        utf8.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
                        utf8.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
                        utf8.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
                        ++k;
                        continue;
                    }
                    utf8.push_back(static_cast<char>(0xE0 | (c >> 12)));
                    utf8.push_back(static_cast<char>(0x80 | ((c >> 6) & 0x3F)));
                    utf8.push_back(static_cast<char>(0x80 | (c & 0x3F)));
                } else {
                    utf8.push_back(static_cast<char>(0xE0 | (c >> 12)));
                    utf8.push_back(static_cast<char>(0x80 | ((c >> 6) & 0x3F)));
                    utf8.push_back(static_cast<char>(0x80 | (c & 0x3F)));
                }
            }
            js = env->NewStringUTF(utf8.c_str());
        }
        env->SetObjectArrayElement(outTexts, i, js);
        env->DeleteLocalRef(js);
    }
    env->ReleaseFloatArrayElements(outBoxes, boxes, 0);
    FPDFText_ClosePage(tp);
    return count;
}

/**
 * Count the per-glyph characters on [page] (includes spaces and generated chars).
 */
JNIEXPORT jint JNICALL
Java_dev_nucleusframework_pdfium_jvm_PdfiumBridge_nCountPageChars(
        JNIEnv*, jclass, jlong page) {
    if (page == 0) return 0;
    FPDF_PAGE p = reinterpret_cast<FPDF_PAGE>(page);
    FPDF_TEXTPAGE tp = FPDFText_LoadPage(p);
    if (!tp) return 0;
    int n = FPDFText_CountChars(tp);
    FPDFText_ClosePage(tp);
    return n;
}

/**
 * Fill [outCodepoints] and [outBoxes] with per-character Unicode + bounding box (in PDF
 * points, origin bottom-left). [outBoxes] is laid out as 4 floats per char: left, bottom,
 * right, top. Returns the number of chars actually written.
 */
JNIEXPORT jint JNICALL
Java_dev_nucleusframework_pdfium_jvm_PdfiumBridge_nExtractCharBoxes(
        JNIEnv* env, jclass, jlong page,
        jintArray outCodepoints, jfloatArray outBoxes) {
    if (page == 0) return 0;
    FPDF_PAGE p = reinterpret_cast<FPDF_PAGE>(page);
    FPDF_TEXTPAGE tp = FPDFText_LoadPage(p);
    if (!tp) return 0;
    int total = FPDFText_CountChars(tp);
    int capacity = env->GetArrayLength(outCodepoints);
    int count = (total < capacity) ? total : capacity;
    if (count == 0) { FPDFText_ClosePage(tp); return 0; }
    jint* codepoints = env->GetIntArrayElements(outCodepoints, nullptr);
    jfloat* boxes = env->GetFloatArrayElements(outBoxes, nullptr);
    for (int i = 0; i < count; i++) {
        codepoints[i] = static_cast<jint>(FPDFText_GetUnicode(tp, i));
        double left = 0, right = 0, bottom = 0, top = 0;
        FPDFText_GetCharBox(tp, i, &left, &right, &bottom, &top);
        boxes[i * 4 + 0] = static_cast<float>(left);
        boxes[i * 4 + 1] = static_cast<float>(bottom);
        boxes[i * 4 + 2] = static_cast<float>(right);
        boxes[i * 4 + 3] = static_cast<float>(top);
    }
    env->ReleaseIntArrayElements(outCodepoints, codepoints, 0);
    env->ReleaseFloatArrayElements(outBoxes, boxes, 0);
    FPDFText_ClosePage(tp);
    return count;
}

} // extern "C"
