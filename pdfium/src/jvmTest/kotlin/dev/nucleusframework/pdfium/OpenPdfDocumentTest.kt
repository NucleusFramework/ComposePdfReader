package dev.nucleusframework.pdfium

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

class OpenPdfDocumentTest {
    /**
     * Regression: bytes PDFium refuses must surface as an exception, not kill the
     * process. The open-failure branch used to free the native buffer that the
     * enclosing `catch` frees as well, so a non-PDF body double-freed it and the
     * host aborted — SIGABRT on the JVM, "Scudo ERROR: invalid chunk state when
     * deallocating" on Android. Reaching the assertion at all IS the test.
     */
    @Test
    fun refusedBytesThrowInsteadOfAbortingTheProcess() = runBlocking {
        assertFailsWith<IllegalStateException> {
            openPdfDocument("definitely not a pdf".encodeToByteArray(), null)
        }
        Unit
    }

    @Test
    fun aValidDocumentStillOpens() = runBlocking {
        val doc = openPdfDocument(minimalPdf(), null)
        try {
            kotlin.test.assertEquals(1, doc.pageCount)
        } finally {
            doc.close()
        }
    }
}

/** A minimal, valid one-page PDF (built-in /Helvetica, no embedded font program). */
private fun minimalPdf(): ByteArray = java.util.Base64.getDecoder().decode(
    "JVBERi0xLjQKMSAwIG9iago8PCAvVHlwZSAvQ2F0YWxvZyAvUGFnZXMgMiAwIFIgPj4KZW5kb2Jq" +
        "CjIgMCBvYmoKPDwgL1R5cGUgL1BhZ2VzIC9LaWRzIFszIDAgUl0gL0NvdW50IDEgPj4KZW5kb2Jq" +
        "CjMgMCBvYmoKPDwgL1R5cGUgL1BhZ2UgL1BhcmVudCAyIDAgUiAvTWVkaWFCb3ggWzAgMCA2MTIg" +
        "NzkyXSAvQ29udGVudHMgNCAwIFIgL1Jlc291cmNlcyA8PCAvRm9udCA8PCAvRjEgNSAwIFIgPj4g" +
        "Pj4gPj4KZW5kb2JqCjQgMCBvYmoKPDwgL0xlbmd0aCAzMzEgPj4Kc3RyZWFtCkJUIC9GMSAyNCBU" +
        "ZiA3MiA3MDAgVGQgMjggVEwgKFRoZSBxdWljayBicm93biBmb3gganVtcHMgb3ZlciB0aGUgbGF6" +
        "eSBkb2cuKSBUaiBUKiAoSW52b2ljZSAjMjAyNi0wNzE0ICBBbW91bnQgZHVlOiAkMSwyMzQuNTYp" +
        "IFRqIFQqIChUaGlzIGlzIGEgdGV4dC1vbmx5IFBERiB0byBleGVyY2lzZSBwZGZpdW0gYXN5bmMg" +
        "Z2x5cGggcGFpbnQuKSBUaiBUKiAoTGluZSBmb3VyIHdpdGggbW9yZSB3b3JkcyB0byBmaWxsIHRo" +
        "ZSBwYWdlIGJvZHkgYXJlYSBuaWNlbHkuKSBUaiBUKiAoQ29udGFjdDogY2xhdWRlQG1pa2VwZW56" +
        "LmRldiAgIFJlZjogUFJFVklFVy1SRVBSTykgVGogVCogRVQKZW5kc3RyZWFtCmVuZG9iago1IDAg" +
        "b2JqCjw8IC9UeXBlIC9Gb250IC9TdWJ0eXBlIC9UeXBlMSAvQmFzZUZvbnQgL0hlbHZldGljYSA+" +
        "PgplbmRvYmoKeHJlZgowIDYKMDAwMDAwMDAwMCA2NTUzNSBmIAowMDAwMDAwMDA5IDAwMDAwIG4g" +
        "CjAwMDAwMDAwNTggMDAwMDAgbiAKMDAwMDAwMDExNSAwMDAwMCBuIAowMDAwMDAwMjQxIDAwMDAw" +
        "IG4gCjAwMDAwMDA2MjMgMDAwMDAgbiAKdHJhaWxlcgo8PCAvU2l6ZSA2IC9Sb290IDEgMCBSID4+" +
        "CnN0YXJ0eHJlZgo2OTMKJSVFT0Y="
)
