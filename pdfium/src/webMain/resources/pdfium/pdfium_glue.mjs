// Main-thread RPC proxy onto the pdfium Web Worker.
// pdfium.wasm + all render / text-extraction work live in pdfium_worker.mjs. The main
// thread only builds requests, waits for responses, and hands the resulting pixel
// ArrayBuffer to Skia via Kotlin. Buffers are transferred both ways (zero-copy) so the
// renderer's 12-MB pixel output never round-trips through a structured-clone copy.

// Resolve the worker URL against the page's base URL, not this module's URL — webpack
// inlines `import.meta.url` at bundle time with the original source's file:// path,
// which the browser then refuses to load from the http://localhost origin.
// pdfium_worker.mjs is served at the bundle root (next to composeApp.js) by the dev
// server and the final dist, so a plain relative URL is enough.
const worker = new Worker('pdfium_worker.mjs', { type: 'module' });

let nextId = 1;
const pending = new Map();

// Gate every RPC until the worker's wasm runtime has finished bootstrapping.
const ready = new Promise((resolve) => {
    const onReady = (e) => {
        if (e.data && e.data.__ready) {
            worker.removeEventListener('message', onReady);
            resolve();
        }
    };
    worker.addEventListener('message', onReady);
});

worker.addEventListener('message', (e) => {
    const msg = e.data;
    if (!msg || typeof msg.id !== 'number') return;
    const p = pending.get(msg.id);
    if (!p) return;
    pending.delete(msg.id);
    if (msg.ok) p.resolve(msg.result);
    else p.reject(new Error(msg.error || 'pdfium worker rejected request'));
});

worker.addEventListener('error', (e) => {
    const err = new Error('pdfium worker error: ' + (e.message || 'unknown'));
    for (const p of pending.values()) p.reject(err);
    pending.clear();
});

async function rpc(op, args, transfer) {
    await ready;
    const id = nextId++;
    return new Promise((resolve, reject) => {
        pending.set(id, { resolve, reject });
        worker.postMessage({ id, op, args }, transfer || []);
    });
}

// ---- exported API ---------------------------------------------------------------------

export function openDocument(buffer, password) {
    // `buffer` is the raw PDF ArrayBuffer — transfer it so the main thread doesn't keep
    // a second copy alive for the document's lifetime.
    return rpc('open', { buffer, password }, [buffer]);
}

export function closeDocument(doc) {
    return rpc('close', { doc });
}

export function pageSize(doc, pageIndex) {
    return rpc('pageSize', { doc, pageIndex });
}

export function renderPage(doc, pageIndex, w, h, flags) {
    return rpc('render', { doc, pageIndex, w, h, flags });
}

export function pageText(doc, pageIndex) {
    return rpc('text', { doc, pageIndex });
}

export function pageTextLayout(doc, pageIndex) {
    return rpc('layout', { doc, pageIndex });
}
