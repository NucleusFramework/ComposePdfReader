package dev.nucleusframework.pdfium

/**
 * Render quality tiers. Controls which PDFium flags are applied.
 *  - [PREVIEW] — fastest, no annotations, no LCD text. For thumbnails and initial progressive frames.
 *  - [FULL] — annotations on, no LCD text. ~15–25% faster than LCD-enabled while still sharp.
 */
enum class RenderQuality { PREVIEW, FULL }
