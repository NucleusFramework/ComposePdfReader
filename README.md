# ComposePdfReader

A Kotlin Multiplatform PDF rendering and text-extraction library built on top of
[bblanchon/pdfium-binaries](https://github.com/bblanchon/pdfium-binaries) and
Compose Multiplatform. Zero-copy render pipeline on every target — on the web
the transferred pixel `ArrayBuffer` is written straight into Skia's wasm heap,
no intermediate Kotlin `ByteArray`. A Compose-first API and a sample
desktop/mobile reader with thumbnails, progressive rendering, and selectable
text round it out.

## Features

- **Compose Multiplatform composables** — drop `PdfPage` or `PdfThumbnail` into
  any Compose UI.
- **Zero-copy rendering** on every target. JVM / Android / iOS hand PDFium a
  raw pixel pointer into Skia / Android `Bitmap` memory. Web allocates the
  destination buffer inside Skia's wasm heap via `Data.makeUninitialized` and
  writes the worker's transferred `ArrayBuffer` straight in — no Kotlin
  `ByteArray` round-trip, no `installPixels` second copy.
- **Progressive rendering** (preview → full) with a debounced size flow, so
  scroll and zoom feel instant.
- **Two-tier LRU cache** (reader bitmaps + thumbnails) with off-screen prefetch.
- **Text extraction** — per-page UTF-8 text, line-level rectangles, and
  per-character bounding boxes.
- **Selectable text overlay** driven by PDFium's per-character boxes, so Ctrl+C
  and long-press copy return the exact PDF text.
- **Clickable links** — link annotations (URI + internal GoTo) plus URLs and
  e-mail addresses auto-detected in the page text (`mailto:` links included).
  External links open through the platform handler, internal links scroll the
  reader to the destination page.
- **Cross-platform fit/zoom controls** via a plain state holder.

## Supported targets

| Target  | Architectures                                                      | Backend                                           |
| ------- | ------------------------------------------------------------------ | ------------------------------------------------- |
| JVM     | linux-x64, linux-arm64, macos-x64, macos-arm64, win-x64, win-arm64 | JNI + Skia (Skiko)                                |
| Android | arm64-v8a, armeabi-v7a, x86, x86_64                                | JNI (NDK `AndroidBitmap_*`)                       |
| iOS     | iosArm64, iosSimulatorArm64                                        | Kotlin/Native cinterop + Skia (Skiko)             |
| Web     | Kotlin/WasmJS, Kotlin/JS (IR)                                      | `pdfium.wasm` in a dedicated Web Worker + Skiko   |

PDFium binaries are fetched automatically at build time from
bblanchon's GitHub releases (pinned in `gradle/libs.versions.toml` →
`pdfium-bblanchon`).

## Installation

Published to Maven Central. Requires Gradle 8.10+ and Kotlin 2.3.20+. The
`:pdfium` module uses a JVM toolchain of 17.

```kotlin
// build.gradle.kts
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("dev.nucleusframework:pdfium:152.0.7934.0b")
        }
    }
}
```

### JVM packaging

When packaging your Compose Desktop app, make sure the generated runtime image
includes the modules needed by FileKit's native file-picker path (only relevant
if you use FileKit):

```kotlin
compose.desktop {
    application {
        nativeDistributions {
            modules("jdk.security.auth", "java.management", "jdk.unsupported")
        }
    }
}
```

### iOS packaging

Nothing to do. A static `libpdfium.a` is packed into the published iOS cinterop
klib, so it is linked straight into your framework — no `linkerOpts`, no
`LIBRARY_SEARCH_PATHS`, no `ThirdParty/` drop, nothing to embed or codesign, and
no extra Xcode build phase. The stock Compose one is enough:

```
./gradlew :composeApp:embedAndSignAppleFrameworkForXcode
```

Both `isStatic = true` and `isStatic = false` frameworks work.

The static archive is not something bblanchon publishes (they ship Apple
platforms as a `.dylib`, which a klib cannot carry). It is built from their own
harness with `build_type=static` by
[NucleusFramework/pdfium-binaries](https://github.com/NucleusFramework/pdfium-binaries),
a fork that tracks upstream releases daily and publishes an `ios-static-<build>`
release per PDFium version; `:pdfium:installPdfiumIos` downloads the archive
matching the `pdfium-bblanchon` pin.

### Web packaging (wasmJS / JS)

For the browser targets, `pdfium.wasm`, `pdfium_worker.mjs`,
`pdfium_runtime.mjs` and `pdfium_glue.mjs` are published as classpath
resources at the artefact root and served from the bundle root. The glue is
eval'd from Kotlin (no webpack `./pdfium_glue.mjs` resolution), so a
consumer using the default Kotlin/JS webpack pipeline needs no extra copy
task. Remember to serve the site over HTTPS (or `localhost`): the Web
Clipboard API used by text copy only works in secure contexts.

## Getting started — a tour

Every snippet below is self-contained and drops straight into a Compose
Multiplatform `commonMain` source set. Paste one, run, then move on to the
next.

### 1. Show a PDF in three lines of logic

```kotlin
@Composable
fun HelloPdf(bytes: ByteArray) {
    val reader = rememberPdfReaderState()       // state holder — dispatches on a background worker
    LaunchedEffect(bytes) { reader.open(bytes) } // parses headers; cancels cleanly if `bytes` changes
    PdfReader(state = reader, modifier = Modifier.fillMaxSize())
}
```

`PdfReader` stacks every page in a `LazyColumn`. `rememberPdfReaderState`
disposes native handles automatically when it leaves composition.

### 2. Load the PDF bytes from somewhere real

**From a platform-native file picker (recommended)** — use
[FileKit](https://github.com/vinceglb/FileKit):

```kotlin
dependencies {
    implementation("io.github.vinceglb:filekit-dialogs-compose:0.13.0")
}
```

```kotlin
@Composable
fun PdfPicker() {
    val reader = rememberPdfReaderState()
    val scope = rememberCoroutineScope()
    val picker = rememberFilePickerLauncher(
        type = FileKitType.File(extensions = listOf("pdf")),
    ) { file ->
        if (file != null) scope.launch { reader.open(file.readBytes()) }
    }
    Column(Modifier.fillMaxSize()) {
        Button(onClick = { picker.launch() }) { Text("Open PDF…") }
        if (reader.pageCount > 0) {
            PdfReader(state = reader, modifier = Modifier.fillMaxSize())
        }
    }
}
```

**From a URL** — use any HTTP client (Ktor here):

```kotlin
val client = remember { HttpClient() }
LaunchedEffect(url) {
    reader.open(client.get(url).readRawBytes())
}
```

**From a classpath resource** — use Compose Resources or your platform's
bundled-asset API. The library only needs a `ByteArray`; how you obtain it is
up to you.

### 3. React to loading state, metadata, and errors

`PdfReaderState` exposes snapshot state you can observe in any composable:

```kotlin
Box(Modifier.fillMaxSize()) {
    when {
        reader.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

        reader.error is PdfError.PasswordRequired -> PasswordPrompt { password ->
            scope.launch { reader.open(bytes, password) }
        }
        reader.error is PdfError.InvalidFormat -> Text("Not a valid PDF")
        reader.error is PdfError.Io -> Text("Couldn't read file: ${reader.error?.message}")
        reader.error is PdfError.NativeFailure -> Text("Render error: ${reader.error?.message}")

        reader.pageCount > 0 -> {
            Column {
                Text(reader.metadata.title ?: "Untitled",
                    style = MaterialTheme.typography.titleLarge)
                Text("by ${reader.metadata.author ?: "Unknown"} — ${reader.pageCount} pages")
                PdfReader(state = reader, modifier = Modifier.weight(1f))
            }
        }
    }
}
```

### 4. Enable copy/paste and text selection

Flip one flag on `PdfPage` and a pixel-precise selection overlay lights up.
Drag on desktop, long-press on mobile, Ctrl/Cmd+C to copy:

```kotlin
PdfPage(
    state = reader,
    pageIndex = pageIndex,
    modifier = Modifier.fillMaxWidth(),
    selectableText = true,   // 👈 all you need
)
```

Hit-testing uses PDFium's per-character boxes (`FPDFText_GetCharBox`) rather
than Compose's own font metrics, so selection tracks the rendered glyphs.

### 4b. Clickable links

Links are enabled by default on `PdfPage` and `PdfReader` — nothing to flip.
Link annotations (`FPDFLink_Enumerate`) and URLs / e-mail addresses detected
in the page text (`FPDFLink_LoadWebLinks`, e-mails become `mailto:`) turn into
clickable regions with a hand cursor on desktop. External URIs open through
`LocalUriHandler`; inside `PdfReader`, internal GoTo links scroll to the
destination page.

Intercept clicks (analytics, custom navigation, blocking) with `onLinkClick` —
return `true` to consume the click and skip the default handling:

```kotlin
PdfReader(
    state = reader,
    onLinkClick = { link ->
        if (link.uri?.startsWith("mailto:") == true) {
            openCustomComposer(link.uri)
            true                      // consumed — default handler skipped
        } else {
            false                     // fall through to default handling
        }
    },
)
```

Fetch links programmatically with `reader.pageLinks(pageIndex)` — each
`PdfLink` carries its bounds in PDF points (origin bottom-left), the target
`uri` (or `null`), and the 0-based `destPageIndex` (or `-1`).

### 5. Extract text programmatically

Grab the full Unicode of a single page:

```kotlin
val scope = rememberCoroutineScope()
scope.launch {
    val text = reader.pageText(pageIndex = 0)
    println(text)
}
```

Concatenate the whole document:

```kotlin
suspend fun dumpPdf(reader: PdfReaderState): String =
    (0 until reader.pageCount).joinToString("\n\n") { reader.pageText(it) }
```

### 6. Search across pages and highlight hits

`pageTextLayout` returns line-level rectangles with their Unicode run — all
you need for a search-in-document feature:

```kotlin
data class SearchHit(val page: Int, val rect: Rect, val text: String)

suspend fun search(reader: PdfReaderState, query: String): List<SearchHit> {
    if (query.length < 2) return emptyList()
    return buildList {
        for (page in 0 until reader.pageCount) {
            val layout = reader.pageTextLayout(page) ?: continue
            for (i in 0 until layout.rectCount) {
                val run = layout.text(i)
                if (run.contains(query, ignoreCase = true)) {
                    // PDF origin is bottom-left; flip Y to Compose top-left.
                    val pageH = layout.pageSize.heightPoints
                    val rect = Rect(
                        left = layout.left(i),
                        top = pageH - layout.top(i),
                        right = layout.right(i),
                        bottom = pageH - layout.bottom(i),
                    )
                    add(SearchHit(page = page, rect = rect, text = run))
                }
            }
        }
    }
}
```

Turn each `SearchHit.rect` (in PDF points) into on-screen pixels with the
scale formula in the [PageTextLayout coordinate guide](#pagetextlayout).

### 7. Draw your own overlay on top of a page

Want to highlight the current search hit, sign a form, or stamp a
watermark? Wrap `PdfPage` in a `Box`, lay a `Canvas` over it, and convert
your PDF-point geometry:

```kotlin
@Composable
fun HighlightedPage(reader: PdfReaderState, pageIndex: Int, hits: List<Rect>) {
    var pageSize by remember { mutableStateOf<PageSize?>(null) }
    LaunchedEffect(pageIndex) { pageSize = reader.pageSize(pageIndex) }

    Box(Modifier.fillMaxWidth()) {
        PdfPage(reader, pageIndex, selectableText = true)
        val size = pageSize ?: return@Box
        Canvas(Modifier.matchParentSize()) {
            val sx = this.size.width  / size.widthPoints
            val sy = this.size.height / size.heightPoints
            hits.forEach { r ->
                drawRect(
                    color = Color(0x665AB1FF),
                    topLeft = Offset(r.left * sx, r.top * sy),
                    size = Size((r.right - r.left) * sx, (r.bottom - r.top) * sy),
                )
            }
        }
    }
}
```

### 8. Add a thumbnail sidebar

`PdfThumbnail` uses `RenderQuality.PREVIEW` and its own LRU, so scrolling a
hundred-page strip never evicts your reader's full-quality bitmaps:

```kotlin
Row(Modifier.fillMaxSize()) {
    LazyColumn(
        modifier = Modifier.width(160.dp).fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(8.dp),
    ) {
        items(reader.pageCount) { i ->
            PdfThumbnail(
                state = reader,
                pageIndex = i,
                modifier = Modifier.clickable { /* jumpToPage(i) */ },
            )
        }
    }
    PdfReader(state = reader, modifier = Modifier.weight(1f).fillMaxHeight())
}
```

### 9. Zoom, fit-to-width, fit-to-page

`PdfReaderState.renderScale` is a plain `Float` — every `PdfPage` observes
it, so flipping it re-renders the visible pages at the new size.

```kotlin
var scale by remember { mutableStateOf(1f) }
LaunchedEffect(scale) { reader.renderScale = scale }

Column {
    Slider(value = scale, onValueChange = { scale = it }, valueRange = 0.5f..3f)
    Row {
        TextButton(onClick = { scale = 1f }) { Text("Fit width") }
        TextButton(onClick = {
            // Maths in ReaderScreenState.kt — 3 lines with the viewport size.
            val vp = viewportPx ; val page = reader.pageSize(0) ?: return@TextButton
            scale = (vp.height * page.aspectRatio / vp.width).coerceIn(0.1f, 4f)
        }) { Text("Fit height") }
    }
}
```

> Looking for a ready-made zoom UI? The sample's
> [`ReaderTopBar.kt`](example/src/commonMain/kotlin/dev/nucleusframework/pdf/reader/ReaderTopBar.kt)
> wires a Material slider + Fit Width / Height / Page buttons you can copy
> as-is.

### 10. Add a right-click / long-press context menu

On desktop, the built-in `ContextMenuArea` works out of the box. Wrap any
page-level composable:

```kotlin
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PageWithMenu(reader: PdfReaderState, pageIndex: Int) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current

    ContextMenuArea(items = {
        listOf(
            ContextMenuItem("Copy page text") {
                scope.launch {
                    val text = reader.pageText(pageIndex)
                    clipboard.setClipEntry(textClipEntry(text))
                }
            },
            ContextMenuItem("Jump to next page") {
                // your own state holder decides how to advance
            },
        )
    }) {
        PdfPage(reader, pageIndex, selectableText = true)
    }
}
```

`textClipEntry(text)` is the lib's own cross-platform helper for the new
Compose `Clipboard.setClipEntry(...)` API (Compose 1.10 deprecated
`ClipboardManager.setText` — this covers the gap).

On Android/iOS, `ContextMenuArea` doesn't exist; detect long-press yourself:

```kotlin
Box(
    Modifier.pointerInput(pageIndex) {
        detectTapGestures(
            onLongPress = { showMenu = true },
        )
    }
) { PdfPage(reader, pageIndex) }
```

### 11. Password-protected PDFs

Pass the password on `open`:

```kotlin
reader.open(bytes, password = "hunter2")
```

If you don't have it yet, `reader.error` will transition to
`PdfError.PasswordRequired` — prompt the user, then re-call `open`:

```kotlin
var pending by remember { mutableStateOf<ByteArray?>(null) }
LaunchedEffect(bytes) { pending = bytes ; reader.open(bytes) }

if (reader.error is PdfError.PasswordRequired && pending != null) {
    PasswordDialog(onSubmit = { pw ->
        scope.launch { reader.open(pending!!, password = pw) }
    })
}
```

### What next?

- The full reader screen (`:example`) wires picker + sidebar + zoom + toast
  in < 500 lines — a real reference for building on top of this library.
- The [API reference](#api-reference) below documents every public symbol
  with its types, defaults, and invariants.

## API reference

All public API lives under the `dev.nucleusframework.pdfium` package in the
`:pdfium` library.

### `PdfReaderState`

The state holder tied to a single PDF document. Hoist it in your screen
composable with `rememberPdfReaderState()`.

```kotlin
@Stable
class PdfReaderState {
    // --- Snapshot state ---
    val pageCount: Int        // 0 until a document is open
    val isLoading: Boolean    // true during open()
    val error: PdfError?      // last open() error, if any
    val metadata: PdfMetadata
    var renderScale: Float    // 1.0 = fit-to-width; scales the size reported to PdfPage

    // --- Intents ---
    suspend fun open(bytes: ByteArray, password: String? = null)
    suspend fun pageSize(pageIndex: Int): PageSize?
    suspend fun pageText(pageIndex: Int): String
    suspend fun pageTextLayout(pageIndex: Int): PageTextLayout?

    // Render ahead-of-display; best-effort, populates the cache.
    fun prefetch(pageIndex: Int, widthPx: Int, quality: RenderQuality = RenderQuality.FULL)

    // Release native handles + cached bitmaps. Called automatically by rememberPdfReaderState.
    fun dispose()

    companion object {
        /** 64 MB. Reader-page LRU — ±2 full-quality bitmaps around the visible page. */
        const val DEFAULT_CACHE_BYTES: Long = 64L * 1024 * 1024

        /** 12 MB. Thumbnail LRU — ~40 × 240-px previews; kept separate from the reader cache. */
        const val DEFAULT_THUMBNAIL_CACHE_BYTES: Long = 12L * 1024 * 1024
    }
}

@Composable
fun rememberPdfReaderState(
    cacheBytes: Long = PdfReaderState.DEFAULT_CACHE_BYTES,
    thumbnailCacheBytes: Long = PdfReaderState.DEFAULT_THUMBNAIL_CACHE_BYTES,
): PdfReaderState
```

Bitmaps are keyed by `(pageIndex, quantized_width)`; both cache budgets are
tunable on `rememberPdfReaderState(...)`.

### `PdfPage`

Composable that renders a single PDF page. Handles progressive rendering
internally (low-res preview → full-quality render on settle) and debounces
size changes so scroll/zoom stay smooth.

```kotlin
@Composable
fun PdfPage(
    state: PdfReaderState,
    pageIndex: Int,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    background: Color = Color.White,
    selectableText: Boolean = false,
    linksEnabled: Boolean = true,
    onLinkClick: ((PdfLink) -> Boolean)? = null,
)
```

- `modifier` controls the layout width; the composable derives the aspect
  ratio from the PDF page and sets its own height.
- `selectableText = true` enables the pointer-driven selection overlay
  described in [step 4](#4-enable-copypaste-and-text-selection).
- `linksEnabled` / `onLinkClick` control the clickable-links overlay described
  in [step 4b](#4b-clickable-links). A standalone `PdfPage` has no list to
  scroll, so internal GoTo links are only actionable through `onLinkClick`.

### `PdfThumbnail`

A low-resolution preview of a single page. Uses `RenderQuality.PREVIEW`, shares
the `PdfReaderState` cache, and sizes itself to the modifier-provided width.

```kotlin
@Composable
fun PdfThumbnail(
    state: PdfReaderState,
    pageIndex: Int,
    modifier: Modifier = Modifier,
    background: Color = Color.White,
)
```

Typical use: a `LazyColumn` / `LazyRow` of thumbnails as a sidebar or bottom
strip next to the main reader.

### `PdfReader`

A convenience composable — a vertical `LazyColumn` that stacks every page of
the document.

```kotlin
@Composable
fun PdfReader(
    state: PdfReaderState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(12.dp),
    pageSpacing: Dp = 16.dp,
)
```

For anything beyond the basics (zoom, thumbnails, responsive layouts), copy
the sample's `ReaderScreen` instead.

### `RenderQuality`

```kotlin
enum class RenderQuality {
    /** No annotations, no LCD text. Used for thumbnails and progressive previews. */
    PREVIEW,

    /** Annotations on, no LCD text. Balanced default for on-screen viewing. */
    FULL,
}
```

### `PageSize`, `PdfMetadata`, `PdfError`

```kotlin
data class PageSize(val widthPoints: Float, val heightPoints: Float) {
    val aspectRatio: Float // widthPoints / heightPoints, or 1f for degenerate pages
}

data class PdfMetadata(
    val title: String? = null,
    val author: String? = null,
    val subject: String? = null,
    val keywords: String? = null,
    val creator: String? = null,
    val producer: String? = null,
)

sealed class PdfError(open val message: String, open val cause: Throwable? = null) {
    data class InvalidFormat(…) : PdfError(…)
    data class PasswordRequired(…) : PdfError(…)
    data class NativeFailure(…) : PdfError(…)
    data class Io(…) : PdfError(…)
}
```

### `PageTextLayout`

Returned by `PdfReaderState.pageTextLayout(…)` for building custom text
overlays or highlighting tools.

```kotlin
@Immutable
class PageTextLayout {
    val pageIndex: Int
    val pageSize: PageSize
    val rectCount: Int
    val charCount: Int

    // Rect-level (line-level runs from FPDFText_GetRect)
    fun left(i: Int): Float       // in PDF points, origin bottom-left
    fun bottom(i: Int): Float
    fun right(i: Int): Float
    fun top(i: Int): Float
    fun text(i: Int): String      // UTF-8 Unicode

    // Char-level (FPDFText_GetCharBox / FPDFText_GetUnicode)
    fun codepoint(i: Int): Int
    fun charLeft(i: Int): Float
    fun charBottom(i: Int): Float
    fun charRight(i: Int): Float
    fun charTop(i: Int): Float
}
```

Coordinates are in PDF page points (1 pt = 1/72 inch), origin at the
bottom-left of the page. To map to a rendered bitmap at pixel dimensions
`W × H`:

```
scaleX = W / pageSize.widthPoints
scaleY = H / pageSize.heightPoints

screenX     = left  × scaleX
screenY     = H - top × scaleY       // flip Y (PDF is bottom-up)
screenW     = (right - left)   × scaleX
screenH     = (top   - bottom) × scaleY
```

## Architecture

```
                                    :pdfium module
┌──────────────────────────────────────────────────────────────────────────────┐
│ commonMain                                                                   │
│   PdfReaderState  ─┐                                                         │
│   PdfPage         ─┼──► expect class PdfDocument                             │
│   PdfThumbnail    ─┘                                                         │
│   PdfRenderCache    PageTextLayout   textClipEntry (expect)                  │
├──────────────────┬───────────────┬─────────────┬─────────────────────────────┤
│ jvmMain          │ androidMain   │ iosMain     │ webMain (js + wasmJs)       │
│   JNI glue       │ JNI + NDK     │ cinterop    │   pdfium.wasm in a Web      │
│   → Skia Bitmap  │ AndroidBitmap │ libpdfium.a │   Worker; RPC via           │
│   zero-copy      │ zero-copy     │ + Skia      │   postMessage transferables │
│                  │               │             │   → Skia heap zero-copy     │
│                  │               │             │ jsMain / wasmJsMain just    │
│                  │               │             │ host a small PlatformBridge │
└──────────────────┴───────────────┴─────────────┴─────────────────────────────┘
```

Key facts:

- **PDFium is single-threaded.** It relies on FreeType's non-thread-safe
  singleton `FT_Library`. Each `PdfDocument` runs on its own single-threaded
  dispatcher, but multiple documents can't be rendered in parallel inside one
  process (tested: crashes in FreeType). Chromium solves this with a separate
  process per document — not currently implemented here.

- **Zero-copy render path.** On JVM and iOS, we get a raw pixel pointer from
  `Bitmap.peekPixels().addr` and pass it to `FPDFBitmap_CreateEx`. PDFium
  writes BGRA pixels straight into Skia's bitmap memory. On Android we lock
  the `android.graphics.Bitmap` via `AndroidBitmap_lockPixels` and do the same.
  On web, the pdfium worker transfers the pixel `ArrayBuffer` to the main
  thread; we allocate the destination inside Skia's own wasm heap via
  `Data.makeUninitialized` and copy the transferred buffer directly there with
  a typed-array `.set()` on the Skia memory view obtained through
  `org.jetbrains.skiko.wasm.awaitSkiko`. One memcpy into the final
  destination, no `installPixels` round-trip. Pattern cribbed from
  [coil3.decode.WebWorker](https://github.com/coil-kt/coil/blob/69b8383a3f95300ddb466afdbe9c54ce2eccb652/coil-core/src/jsCommonMain/kotlin/coil3/decode/WebWorker.kt).

- **Native binary delivery.** `pdfium/build.gradle.kts` registers a set of
  Gradle tasks that download the bblanchon archives, extract them, and stage
  them as classpath resources (JVM) / jniLibs (Android) / root-level wasm+JS
  assets (web). iOS instead downloads the static archive from the
  `pdfium-binaries` fork and lets cinterop pack it into the klib
  (`staticLibraries` in `pdfium.def`). The JNI glue is rebuilt from
  `pdfium_jni.cpp` via `build-linux.sh` / `build-macos.sh` /
  `build-windows.bat`.

- **Shared document buffer.** The JVM/Android path copies the PDF bytes into
  a native buffer once via `nAllocBuffer`, then hands that buffer address to
  `nOpenDocumentFromMemory` for the document handle. Closing the document
  frees the buffer.

## Sample app (`:example`)

The sample is a full PDF reader with:

- Responsive layout (thumbnail sidebar ≥ 760 dp, bottom strip otherwise)
- Top bar with file name, page counter, zoom slider, `Fit Width` /
  `Fit Height` / `Fit Page` buttons
- Continuous scroll reader with horizontal-scroll when zoomed in, prefetch
  ±2 pages, selection overlay
- File picking via [FileKit](https://github.com/vinceglb/FileKit)
- Compose-Unstyled atoms (no Material 3 dependency)

Source layout:

```
example/src/commonMain/kotlin/dev/nucleusframework/pdf/
├── App.kt                        ─ root composable, wires picker + screen
├── design/
│   ├── Theme.kt                  ─ Palette / Typography / Shapes + LocalAppTheme
│   ├── Atoms.kt                  ─ AppText, PrimaryButton, GhostButton, Spinner…
│   ├── MinimalSlider.kt
│   └── ToastOverlay.kt
└── reader/
    ├── ReaderScreenState.kt      ─ plain @Stable state holder + intents
    ├── ReaderScreen.kt           ─ screen + wide/narrow layouts
    ├── ReaderTopBar.kt           ─ file/page info + zoom + fit controls
    ├── ReaderThumbnails.kt       ─ LazyColumn / LazyRow of PdfThumbnail
    ├── ReaderSurface.kt          ─ continuous scroll view + per-page card
    ├── ReaderEmptyState.kt
    └── TextSelectionDialog.kt    ─ modal with SelectionContainer fallback
```

## Build and run

Run requirements: Gradle wrapper, JDK 17+, internet access on first build
(to download bblanchon archives).

### Desktop (JVM)

```
./gradlew :example:run
```

A native runtime image with modules is built by
`./gradlew :example:createDistributable` and runnable via
`:example:runDistributable`.

### Android

```
./gradlew :example:assembleDebug
./gradlew :example:installDebug
```

First-time Android builds also run `:pdfium:installPdfiumAndroidJniLibs` which
drops `libpdfium.so` into `src/androidMain/jniLibs/<abi>/`.

### iOS

Open `iosApp/` in Xcode and run. The Gradle side has to run on a macOS host
for the framework link to succeed (the cinterop itself cross-compiles from any
host). The Xcode "Compile Kotlin Framework" phase is the stock
`embedAndSignAppleFrameworkForXcode` — PDFium is already inside the framework.

### Smoke test

A headless Linux JVM smoke test renders a PDF, extracts text, and fires 192
concurrent render calls to stress the serialised dispatcher:

```
./gradlew :pdfium:smokeTest -PpdfPath=/absolute/path/to/some.pdf
```

Leaves out `-PpdfPath` and it falls back to `/usr/share/cups/data/classified.pdf`
if available.

## Known limitations

- **No cross-process parallel rendering.** PDFium + FreeType is effectively
  single-threaded per process. Rendering is serialised inside each document.
- **Selection text precision.** The overlay uses PDFium's per-character
  bounding boxes, not glyph positioning from the embedded PDF font. Copied
  text is exact, but highlight rectangles can differ slightly from what
  Chrome / PDF.js render when they can access the original font metrics.
- **Web: still one memcpy per render.** "Zero-copy" here means "no
  intermediate Kotlin `ByteArray`, no `installPixels`" — the worker's
  transferred `ArrayBuffer` is written straight into Skia's wasm heap. A true
  zero-memcpy pipeline would need `SharedArrayBuffer` (which in turn requires
  COOP/COEP headers) so the pdfium worker and Skia share one linear memory.
  Not currently implemented.
- **Licensing.** PDFium is dual-licensed BSD-3-Clause / Apache-2.0 (see
  PDFium's `LICENSE`). bblanchon's binaries carry that license forward. If
  you ship this code, include the upstream PDFium notices.

## License

This repository ships build tooling and Kotlin code that wraps PDFium. The
PDFium binaries themselves are governed by the upstream BSD-3-Clause /
Apache-2.0 license. No license file is committed here yet — treat the
wrapper code as unlicensed pending a decision.
