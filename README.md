# PdfiumKt

A Kotlin Multiplatform PDF rendering and text-extraction library built on top of
[bblanchon/pdfium-binaries](https://github.com/bblanchon/pdfium-binaries) and
Compose Multiplatform. Ships a zero-copy render pipeline, a Compose-first API,
and a sample desktop/mobile reader with thumbnails, progressive rendering, and
selectable text.

## Features

- **Compose Multiplatform composables** — drop `PdfPage` or `PdfThumbnail` into
  any Compose UI.
- **Zero-copy rendering** on JVM / Android / iOS: PDFium writes directly into
  Skia / Android `Bitmap` pixel memory — no intermediate `ByteArray` or
  `ByteBuffer` copies.
- **Progressive rendering** (preview → full) with a debounced size flow, so
  scroll and zoom feel instant.
- **Per-document render cache** (LRU, budget-limited) and off-screen prefetch.
- **Text extraction** — per-page UTF-8 text, line-level rectangles, and
  per-character bounding boxes.
- **Selectable text overlay** built on `SelectionContainer` so Ctrl+C and
  long-press copy return the exact PDF text.
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

Add the Maven Central repository and the dependency:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}
```

```kotlin
// app/build.gradle.kts
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("dev.nucleusframework.pdf:pdfium:0.1.0")
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

### Web packaging (wasmJS / JS)

For the browser targets, the `pdfium.wasm` + worker assets are published as
classpath resources inside the library artifact and served from the module
root. If you bundle your app with the default Kotlin/JS webpack pipeline, no
extra configuration is needed — the `@JsModule("./pdfium_glue.mjs")` imports
resolve against your webpack output directory. Remember to serve the site over
HTTPS (or `localhost`): the Web Clipboard API used by text copy only works in
secure contexts.

## Quick start

```kotlin
@Composable
fun MyPdfViewer(bytes: ByteArray) {
    val reader = rememberPdfReaderState()
    LaunchedEffect(bytes) { reader.open(bytes) }

    LazyColumn {
        items(reader.pageCount) { pageIndex ->
            PdfPage(
                state = reader,
                pageIndex = pageIndex,
                modifier = Modifier.fillMaxWidth(),
                selectableText = true,
            )
        }
    }
}
```

That is the whole integration: open a PDF, scroll through pages, select text
with the mouse or long-press. The sample in `:example` shows how to wire
[FileKit](https://github.com/vinceglb/FileKit) for file picking, add a
thumbnail sidebar, responsive layouts, and fit-width/height/page controls.

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

The reader keeps two LRUs of `ImageBitmap`s keyed by
`(pageIndex, quantized_width)`: one for full-quality reader pages, one for
`PdfThumbnail` previews. Separating them means scrolling a 100-page thumbnail
strip doesn't evict the reader's main-page bitmaps. Both budgets are tunable
on `rememberPdfReaderState(...)`.

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
)
```

- `modifier` controls the layout width; the composable derives the aspect
  ratio from the PDF page and sets its own height.
- `selectableText = true` overlays a transparent, selectable text layer on top
  of the bitmap so the user can drag-select and copy. The overlay uses per-text
  rectangles extracted via PDFium's `FPDFText_GetRect`. Copy returns the exact
  Unicode reported by PDFium.

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
│ jvmMain          │ androidMain   │ iosMain     │ jsMain / wasmJsMain (web)   │
│   JNI glue       │ JNI + NDK     │ cinterop    │ pdfium.wasm in a Web Worker │
│   → Skia Bitmap  │ AndroidBitmap │ libpdfium.a │ RPC via postMessage,        │
│   zero-copy      │ zero-copy     │ + Skia      │ transferable pixels → Skia  │
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

- **Native binary delivery.** `pdfium/build.gradle.kts` registers a set of
  Gradle tasks that download the bblanchon archives, extract them, and stage
  them as classpath resources (JVM) / jniLibs (Android) / static libs
  (iOS cinterop). The JNI glue is rebuilt from `pdfium_jni.cpp` via
  `build-linux.sh` / `build-macos.sh` / `build-windows.bat`.

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
for the cinterop + framework link to succeed.

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
- **Selection text precision.** The overlay uses PDFium's line-level
  rectangles, not per-glyph positioning with the original PDF font. Selection
  bounds match the line, and copied text is exact, but the highlight
  rectangles won't align glyph-for-glyph the way Chrome / PDF.js do when they
  can match an embedded PDF font.
- **Web: no zero-copy to Skia.** On wasmJs/JS, `pdfium.wasm` runs inside a
  dedicated Web Worker (so the main thread never blocks). Pixels are posted
  to the main thread via `postMessage` transferables and bulk-copied once
  into a Skia `Bitmap` via `installPixels` — the only unavoidable copy
  in the pipeline, since Skia has its own wasm heap with no direct
  `ArrayBuffer` install.
- **Licensing.** PDFium is dual-licensed BSD-3-Clause / Apache-2.0 (see
  PDFium's `LICENSE`). bblanchon's binaries carry that license forward. If
  you ship this code, include the upstream PDFium notices.

## License

This repository ships build tooling and Kotlin code that wraps PDFium. The
PDFium binaries themselves are governed by the upstream BSD-3-Clause /
Apache-2.0 license. No license file is committed here yet — treat the
wrapper code as unlicensed pending a decision.
