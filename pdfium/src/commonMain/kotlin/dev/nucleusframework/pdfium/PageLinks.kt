package dev.nucleusframework.pdfium

import androidx.compose.runtime.Immutable

/**
 * A clickable region on a PDF page. Coordinates are in PDF page points with origin at the
 * bottom-left of the page (same space as [PageTextLayout]).
 *
 * Exactly one target is typically set:
 *  - [uri]: external target (`https://…`, `mailto:…`) from a URI action, or auto-detected
 *    in the page text by PDFium's web-link extractor (which also recognizes e-mail
 *    addresses and prefixes them with `mailto:`)
 *  - [destPageIndex]: 0-based target page of an internal GoTo destination, or -1 if none
 */
@Immutable
data class PdfLink(
    val left: Float,
    val bottom: Float,
    val right: Float,
    val top: Float,
    val uri: String? = null,
    val destPageIndex: Int = -1,
)

/** All clickable links of a single page, from link annotations and text-detected URLs. */
@Immutable
class PageLinks internal constructor(
    val pageIndex: Int,
    val pageSize: PageSize,
    val links: List<PdfLink>,
) {
    companion object {
        val Empty = PageLinks(pageIndex = -1, pageSize = PageSize(0f, 0f), links = emptyList())
    }
}

/**
 * Merge annotation links with text-detected web links. A web link whose center falls inside
 * an annotation rect is dropped — authors commonly place a link annotation over the printed
 * URL, and the annotation's action is authoritative.
 */
internal fun buildPageLinks(
    pageIndex: Int,
    pageSize: PageSize,
    annotationLinks: List<PdfLink>,
    webLinks: List<PdfLink>,
): PageLinks {
    val merged = if (webLinks.isEmpty()) {
        annotationLinks
    } else {
        annotationLinks + webLinks.filter { web ->
            val cx = (web.left + web.right) * 0.5f
            val cy = (web.bottom + web.top) * 0.5f
            annotationLinks.none { cx in it.left..it.right && cy in it.bottom..it.top }
        }
    }
    return PageLinks(pageIndex, pageSize, merged)
}

/**
 * Build [PageLinks] from the flat arrays the JNI bridge fills: 4 floats per link in [boxes]
 * (left, bottom, right, top in PDF points), the target URI or null in [uris], the 0-based
 * GoTo page or -1 in [destPages], and whether the entry was text-detected in [isWeb].
 */
internal fun pageLinksFromArrays(
    pageIndex: Int,
    pageSize: PageSize,
    boxes: FloatArray,
    uris: Array<String?>,
    destPages: IntArray,
    isWeb: BooleanArray,
    count: Int,
): PageLinks {
    val annotationLinks = ArrayList<PdfLink>(count)
    val webLinks = ArrayList<PdfLink>()
    for (i in 0 until count) {
        val link = PdfLink(
            left = boxes[i * 4],
            bottom = boxes[i * 4 + 1],
            right = boxes[i * 4 + 2],
            top = boxes[i * 4 + 3],
            uri = uris[i],
            destPageIndex = destPages[i],
        )
        if (isWeb[i]) webLinks.add(link) else annotationLinks.add(link)
    }
    return buildPageLinks(pageIndex, pageSize, annotationLinks, webLinks)
}
