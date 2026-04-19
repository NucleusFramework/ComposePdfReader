package dev.nucleusframework.pdfium

import androidx.compose.runtime.Immutable

/**
 * Extracted text geometry of a single page, at two granularities:
 *  - **rect-level**: line-level runs from `FPDFText_GetRect` with their bounded UTF-8 text
 *    (useful for whole-page text extraction and coarse overlays)
 *  - **char-level**: per-glyph codepoint + bounding box from `FPDFText_GetCharBox`
 *    (used by the precision selection overlay — one composable per character)
 *
 * Coordinates are in PDF page points with origin at the bottom-left of the page.
 */
@Immutable
class PageTextLayout internal constructor(
    val pageIndex: Int,
    val pageSize: PageSize,
    private val rectBoxes: FloatArray,       // 4 × rectCount: [left, bottom, right, top]
    private val rectTexts: Array<String>,
    private val charCodepoints: IntArray,    // N
    private val charBoxes: FloatArray,       // 4 × N: [left, bottom, right, top]
) {
    val rectCount: Int get() = rectTexts.size
    val charCount: Int get() = charCodepoints.size

    // Rect-level API (line-level runs).
    fun left(index: Int): Float = rectBoxes[index * 4]
    fun bottom(index: Int): Float = rectBoxes[index * 4 + 1]
    fun right(index: Int): Float = rectBoxes[index * 4 + 2]
    fun top(index: Int): Float = rectBoxes[index * 4 + 3]
    fun text(index: Int): String = rectTexts[index]

    // Char-level API.
    fun codepoint(index: Int): Int = charCodepoints[index]
    fun charLeft(index: Int): Float = charBoxes[index * 4]
    fun charBottom(index: Int): Float = charBoxes[index * 4 + 1]
    fun charRight(index: Int): Float = charBoxes[index * 4 + 2]
    fun charTop(index: Int): Float = charBoxes[index * 4 + 3]

    companion object {
        val Empty = PageTextLayout(
            pageIndex = -1,
            pageSize = PageSize(0f, 0f),
            rectBoxes = FloatArray(0),
            rectTexts = emptyArray(),
            charCodepoints = IntArray(0),
            charBoxes = FloatArray(0),
        )
    }
}
