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

| Target  | Architectures                                 | Backend                               |
| ------- | --------------------------------------------- | ------------------------------------- |
| JVM     | linux-x64, linux-arm64, macos-x64, macos-arm64, win-x64, win-arm64 | JNI + Skia (Skiko)                    |
| Android | arm64-v8a, armeabi-v7a, x86, x86_64           | JNI (NDK `AndroidBitmap_*`)           |
| iOS     | iosArm64, iosSimulatorArm64                   | Kotlin/Native cinterop + Skia (Skiko) |

PDFium binaries are fetched automatically at build time from
bblanchon's GitHub releases (pinned in `gradle/libs.versions.toml` →
`pdfium-bblanchon`).

## Installation

The library lives in the `:pdfium` Gradle module of this repository. To use it
in your own app, either publish it to a Maven repository or include it as a
composite build / git submodule. Once available, declare:

```kotlin
// settings.gradle.kts
include(":pdfium")

// app/build.gradle.kts
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.pdfium) // or implementation("dev.nucleusframework:pdfium:<version>")
        }
    }
}
```

Gradle 9.4.1+ and Kotlin 2.3.20+ are required. The `:pdfium` module sets a JVM
toolchain of 17.

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
class PdfReaderState internal constructor(cacheBytes: Long = DEFAULT_CACHE_BYTES) {
    // --- Snapshot state ---
    var pageCount: Int      // 0 until a document is open
    var isLoading: Boolean  // true during open()
    var error: PdfError?    // last open() error, if any
    var metadata: PdfMetadata
    var renderScale: Float  // 1.0 = fit-to-width; scales the size reported to PdfPage

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
        const val DEFAULT_CACHE_BYTES: Long = 150L * 1024 * 1024
    }
}

@Composable
fun rememberPdfReaderState(
    cacheBytes: Long = PdfReaderState.DEFAULT_CACHE_BYTES,
): PdfReaderState
```

The cache is a per-document LRU of `ImageBitmap`s keyed by
`(pageIndex, quantized_width)`. Hits avoid re-rendering entirely. Adjust the
byte budget via `rememberPdfReaderState(cacheBytes = …)`.

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
┌─────────────────────────────────────────────────────────────┐
│ commonMain                                                  │
│   PdfReaderState  ─┐                                        │
│   PdfPage         ─┼──► expect class PdfDocument            │
│   PdfThumbnail    ─┘         │                              │
│   PdfRenderCache            │                              │
│   PageTextLayout            │                              │
├──────────────────────────────┼──────────────────────────────┤
│ jvmMain                     │   androidMain   │   iosMain   │
│   JNI via shared C++ glue   │   JNI + NDK     │   cinterop  │
│   (pdfium_jni.cpp)          │   AndroidBitmap │   to libpdfium
│   Writes into Skia Bitmap   │   zero-copy     │   static lib
│   pixel memory directly     │                 │             │
└──────────────────────────────┴─────────────────┴─────────────┘
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
- **WASM / Web targets are not supported.** Earlier iterations targeted
  wasmJs through bblanchon's emscripten build; the memory boundary between
  `pdfium.wasm` and `skiko.wasm` prevents a zero-copy path and the approach
  was dropped.
- **Licensing.** PDFium is dual-licensed BSD-3-Clause / Apache-2.0 (see
  PDFium's `LICENSE`). bblanchon's binaries carry that license forward. If
  you ship this code, include the upstream PDFium notices.

## License

This repository ships build tooling and Kotlin code that wraps PDFium. The
PDFium binaries themselves are governed by the upstream BSD-3-Clause /
Apache-2.0 license. No license file is committed here yet — treat the
wrapper code as unlicensed pending a decision.
