package dev.nucleusframework.pdfium

sealed class PdfError(open val message: String, open val cause: Throwable? = null) {
    data class InvalidFormat(override val message: String = "Invalid or corrupted PDF") : PdfError(message)
    data class PasswordRequired(override val message: String = "PDF is password-protected") : PdfError(message)
    data class NativeFailure(override val message: String, override val cause: Throwable? = null) : PdfError(message, cause)
    data class Io(override val message: String, override val cause: Throwable? = null) : PdfError(message, cause)
}
