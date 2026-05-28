// Dedicated Web Worker for pdfium.
// Runs the entire pdfium pipeline (document open, page rendering, text extraction) off
// the main thread so the browser UI stays responsive while PDFium chews through a
// multi-MB render. Communication is strict request/response with numeric ids — the main
// side is in pdfium_glue.mjs.
//
// Rendered pixel buffers and extracted typed arrays are posted back as transferables so
// the main thread receives them with zero-copy ownership transfer. The only unavoidable
// main-thread copy is the final Skia `installPixels` (bytes ⇒ Skia's own wasm heap).

import { initPdfium } from './pdfium_runtime.mjs';

const M = await initPdfium({
    locateFile: (p) => p,
    print: () => {},
    printErr: () => {},
});
M._FPDF_InitLibrary();

// Track the raw-buffer pointer we allocate for each open document so close() can free
// it without an extra round-trip.
const bufferByDoc = new Map();
// Form-fill environment handle + the FPDF_FORMFILLINFO backing struct per document.
// PDFium retains a borrowed pointer to the struct for the form handle's lifetime, so
// both must be freed in lockstep with the document.
const formByDoc = new Map();
const formInfoByDoc = new Map();

// ---- low-level helpers ---------------------------------------------------------------

function allocUtf8(str) {
    if (str == null) return 0;
    const bytes = new TextEncoder().encode(str);
    const ptr = M._malloc(bytes.length + 1);
    M.HEAPU8.set(bytes, ptr);
    M.HEAPU8[ptr + bytes.length] = 0;
    return ptr;
}

function readUtf16LEFromPtr(ptr, charLen) {
    const view = M.HEAPU8;
    const end = ptr + charLen * 2;
    let out = '';
    let chunk = [];
    for (let p = ptr; p < end; p += 2) {
        chunk.push(view[p] | (view[p + 1] << 8));
        if (chunk.length === 4096) {
            out += String.fromCharCode.apply(null, chunk);
            chunk = [];
        }
    }
    if (chunk.length) out += String.fromCharCode.apply(null, chunk);
    return out;
}

function getMetaText(doc, tag) {
    const tagPtr = allocUtf8(tag);
    try {
        const needed = M._FPDF_GetMetaText(doc, tagPtr, 0, 0);
        if (needed <= 2) return null;
        const buf = M._malloc(needed);
        try {
            M._FPDF_GetMetaText(doc, tagPtr, buf, needed);
            return readUtf16LEFromPtr(buf, (needed / 2) - 1);
        } finally {
            M._free(buf);
        }
    } finally {
        M._free(tagPtr);
    }
}

// ---- operations ----------------------------------------------------------------------

const FPDFBitmap_BGRA = 4;
const FPDF_ANNOT_FLAG = 0x01;
// FPDF_FORMFILLINFO is a struct of ~45 function pointers + a leading `int version`. In the
// WASM32 ABI each is 4 bytes, so 1024 bytes is generous. We zero-fill everything and set
// only version=2 — that leaves every callback as null, which PDFium treats as a no-op for
// static (read-only) widget rendering.
const FORMFILLINFO_SIZE = 1024;

function initFormEnv(doc) {
    const infoPtr = M._malloc(FORMFILLINFO_SIZE);
    M.HEAPU8.fill(0, infoPtr, infoPtr + FORMFILLINFO_SIZE);
    M.HEAP32[infoPtr >> 2] = 2; // FPDF_FORMFILLINFO.version
    const formHandle = M._FPDFDOC_InitFormFillEnvironment(doc, infoPtr);
    if (!formHandle) {
        M._free(infoPtr);
        return { formHandle: 0, infoPtr: 0 };
    }
    return { formHandle, infoPtr };
}

function openDocument({ buffer, password }) {
    const u8 = new Uint8Array(buffer);
    const ptr = M._malloc(u8.byteLength);
    M.HEAPU8.set(u8, ptr);
    const pw = password ? allocUtf8(password) : 0;
    try {
        const doc = M._FPDF_LoadMemDocument(ptr, u8.byteLength, pw);
        if (!doc) {
            M._free(ptr);
            throw new Error('PDFium refused to open document (err=' + M._FPDF_GetLastError() + ')');
        }
        bufferByDoc.set(doc, ptr);
        const { formHandle, infoPtr } = initFormEnv(doc);
        if (formHandle) {
            formByDoc.set(doc, formHandle);
            formInfoByDoc.set(doc, infoPtr);
        }
        return {
            result: {
                doc,
                pageCount: M._FPDF_GetPageCount(doc),
                title: getMetaText(doc, 'Title'),
                author: getMetaText(doc, 'Author'),
                subject: getMetaText(doc, 'Subject'),
                keywords: getMetaText(doc, 'Keywords'),
                creator: getMetaText(doc, 'Creator'),
                producer: getMetaText(doc, 'Producer'),
            },
        };
    } finally {
        if (pw) M._free(pw);
    }
}

function closeDocument({ doc }) {
    // Form-fill env must be torn down before the document — PDFium dereferences the doc
    // inside FPDFDOC_ExitFormFillEnvironment.
    const formHandle = formByDoc.get(doc);
    if (formHandle) {
        M._FPDFDOC_ExitFormFillEnvironment(formHandle);
        formByDoc.delete(doc);
    }
    const infoPtr = formInfoByDoc.get(doc);
    if (infoPtr) { M._free(infoPtr); formInfoByDoc.delete(doc); }
    M._FPDF_CloseDocument(doc);
    const ptr = bufferByDoc.get(doc);
    if (ptr) { M._free(ptr); bufferByDoc.delete(doc); }
    return { result: {} };
}

function pageSize({ doc, pageIndex }) {
    const page = M._FPDF_LoadPage(doc, pageIndex);
    if (!page) return { result: { widthPoints: 0, heightPoints: 0 } };
    try {
        return {
            result: {
                widthPoints: M._FPDF_GetPageWidthF(page),
                heightPoints: M._FPDF_GetPageHeightF(page),
            },
        };
    } finally {
        M._FPDF_ClosePage(page);
    }
}

function renderPage({ doc, pageIndex, w, h, flags }) {
    const page = M._FPDF_LoadPage(doc, pageIndex);
    if (!page) throw new Error('PDFium load page ' + pageIndex + ' failed (err=' + M._FPDF_GetLastError() + ')');
    try {
        const stride = w * 4;
        const size = stride * h;
        const pixels = M._malloc(size);
        M.HEAPU8.fill(0xff, pixels, pixels + size);
        const bmp = M._FPDFBitmap_CreateEx(w, h, FPDFBitmap_BGRA, pixels, stride);
        if (!bmp) {
            M._free(pixels);
            throw new Error('PDFium bitmap creation failed');
        }
        try {
            M._FPDF_RenderPageBitmap(bmp, page, 0, 0, w, h, 0, flags);
            // Form widget overlay (signatures, fillable fields). Only when FPDF_ANNOT is set
            // (RenderQuality.FULL) — PREVIEW thumbnails skip this extra pass for speed.
            const formHandle = (flags & FPDF_ANNOT_FLAG) ? formByDoc.get(doc) : 0;
            if (formHandle) {
                M._FORM_OnAfterLoadPage(page, formHandle);
                M._FPDF_FFLDraw(formHandle, bmp, page, 0, 0, w, h, 0, flags);
                M._FORM_OnBeforeClosePage(page, formHandle);
            }
            // Detach via .slice so freeing pdfium's buffer below doesn't dangle.
            const pixelBuffer = M.HEAPU8.slice(pixels, pixels + size).buffer;
            return { result: { pixels: pixelBuffer }, transfer: [pixelBuffer] };
        } finally {
            M._FPDFBitmap_Destroy(bmp);
            M._free(pixels);
        }
    } finally {
        M._FPDF_ClosePage(page);
    }
}

function pageText({ doc, pageIndex }) {
    const page = M._FPDF_LoadPage(doc, pageIndex);
    if (!page) return { result: { text: '' } };
    try {
        const tp = M._FPDFText_LoadPage(page);
        if (!tp) return { result: { text: '' } };
        try {
            const count = M._FPDFText_CountChars(tp);
            if (count <= 0) return { result: { text: '' } };
            const buf = M._malloc((count + 1) * 2);
            try {
                const written = M._FPDFText_GetText(tp, 0, count, buf);
                const text = written > 0 ? readUtf16LEFromPtr(buf, written - 1) : '';
                return { result: { text } };
            } finally {
                M._free(buf);
            }
        } finally {
            M._FPDFText_ClosePage(tp);
        }
    } finally {
        M._FPDF_ClosePage(page);
    }
}

function pageTextLayout({ doc, pageIndex }) {
    const page = M._FPDF_LoadPage(doc, pageIndex);
    if (!page) {
        return { result: emptyLayout() };
    }
    try {
        const widthPoints = M._FPDF_GetPageWidthF(page);
        const heightPoints = M._FPDF_GetPageHeightF(page);
        const tp = M._FPDFText_LoadPage(page);
        if (!tp) return { result: emptyLayout(widthPoints, heightPoints) };
        try {
            const scratch = M._malloc(32); // 4 × f64 scratch for GetRect / GetCharBox
            try {
                // -- rect level --
                const rectCount = M._FPDFText_CountRects(tp, 0, -1);
                const rectBoxes = new Float32Array(rectCount * 4);
                const rectTexts = new Array(rectCount);
                for (let i = 0; i < rectCount; i++) {
                    const ok = M._FPDFText_GetRect(tp, i, scratch, scratch + 8, scratch + 16, scratch + 24);
                    if (!ok) { rectTexts[i] = ''; continue; }
                    const dv = new DataView(M.HEAPU8.buffer, scratch, 32);
                    const left = dv.getFloat64(0, true);
                    const top = dv.getFloat64(8, true);
                    const right = dv.getFloat64(16, true);
                    const bottom = dv.getFloat64(24, true);
                    rectBoxes[i * 4]     = left;
                    rectBoxes[i * 4 + 1] = bottom;
                    rectBoxes[i * 4 + 2] = right;
                    rectBoxes[i * 4 + 3] = top;

                    const cap = 2048;
                    const textBuf = M._malloc(cap * 2);
                    try {
                        const written = M._FPDFText_GetBoundedText(tp, left, top, right, bottom, textBuf, cap);
                        rectTexts[i] = written > 0 ? readUtf16LEFromPtr(textBuf, written) : '';
                    } finally {
                        M._free(textBuf);
                    }
                }

                // -- char level --
                const charCount = M._FPDFText_CountChars(tp);
                const charCodepoints = new Int32Array(charCount);
                const charBoxes = new Float32Array(charCount * 4);
                for (let i = 0; i < charCount; i++) {
                    charCodepoints[i] = M._FPDFText_GetUnicode(tp, i);
                    M._FPDFText_GetCharBox(tp, i, scratch, scratch + 8, scratch + 16, scratch + 24);
                    const dv = new DataView(M.HEAPU8.buffer, scratch, 32);
                    charBoxes[i * 4]     = dv.getFloat64(0, true);  // left
                    charBoxes[i * 4 + 1] = dv.getFloat64(16, true); // bottom
                    charBoxes[i * 4 + 2] = dv.getFloat64(8, true);  // right
                    charBoxes[i * 4 + 3] = dv.getFloat64(24, true); // top
                }

                return {
                    result: {
                        widthPoints,
                        heightPoints,
                        rectBoxes,
                        rectTexts,
                        charCodepoints,
                        charBoxes,
                    },
                    transfer: [rectBoxes.buffer, charCodepoints.buffer, charBoxes.buffer],
                };
            } finally {
                M._free(scratch);
            }
        } finally {
            M._FPDFText_ClosePage(tp);
        }
    } finally {
        M._FPDF_ClosePage(page);
    }
}

function emptyLayout(widthPoints = 0, heightPoints = 0) {
    return {
        widthPoints,
        heightPoints,
        rectBoxes: new Float32Array(0),
        rectTexts: [],
        charCodepoints: new Int32Array(0),
        charBoxes: new Float32Array(0),
    };
}

// ---- dispatch ------------------------------------------------------------------------

const handlers = {
    open: openDocument,
    close: closeDocument,
    pageSize,
    render: renderPage,
    text: pageText,
    layout: pageTextLayout,
};

self.onmessage = (e) => {
    const { id, op, args } = e.data;
    const fn = handlers[op];
    if (!fn) {
        self.postMessage({ id, ok: false, error: 'unknown op: ' + op });
        return;
    }
    try {
        const { result, transfer } = fn(args) ?? { result: {} };
        self.postMessage({ id, ok: true, result }, transfer || []);
    } catch (err) {
        self.postMessage({ id, ok: false, error: err && err.message ? err.message : String(err) });
    }
};

// Signal readiness. The main-side glue gates every RPC behind this handshake.
self.postMessage({ __ready: true });
