package dev.nucleusframework.pdfium

import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Regression for https://github.com/NucleusFramework/ComposePdfReader/issues/11
 *
 * Maven consumers of `:pdfium` were getting `ld: library 'pdfium' not found` on iOS
 * and webpack `Can't resolve './pdfium_glue.mjs'` on web, because the published
 * klibs shipped Kotlin bindings but not the native/wasm binaries the linker and
 * bundler actually need. Android / JVM already embed their natives; this test
 * locks in the same for iOS cinterop and the JS/Wasm artefacts.
 */
class PackagedBinariesTest {

    @Test
    fun iosCinteropKlibsEmbedLibpdfium() {
        val klibs = artefactFiles("pdfium.ios.cinterop.klibs")
        assertTrue(klibs.isNotEmpty(), "no iOS cinterop klibs were handed to the test")
        klibs.forEach { klib ->
            val names = artefactNames(klib)
            assertTrue(
                names.any { it.substringAfterLast('/') == "libpdfium.a" },
                "${klib.name} is missing libpdfium.a (issue #11). Entries:\n${names.joinToString("\n")}",
            )
            val manifest = artefactText(klib, "default/manifest")
            assertTrue(
                !manifest.contains("-lpdfium"),
                "${klib.name} exports `-lpdfium` instead of relying on the packed archive, " +
                    "so Maven consumers fail with `ld: library 'pdfium' not found`.\n$manifest",
            )
        }
    }

    @Test
    fun webKlibsShipGlueWorkerRuntimeAndWasmAtArchiveRoot() {
        val roots = artefactFiles("pdfium.web.klibs")
        assertTrue(roots.isNotEmpty(), "no JS/Wasm resource roots were handed to the test")
        val required = listOf(
            "pdfium_glue.mjs",
            "pdfium_worker.mjs",
            "pdfium_runtime.mjs",
            "pdfium.wasm",
        )
        roots.forEach { root ->
            val names = artefactNames(root)
            required.forEach { file ->
                // Webpack resolves `@JsModule("./pdfium_glue.mjs")` against the kotlin
                // package dir / dist root. A nested `pdfium/pdfium_glue.mjs` is
                // invisible to that import (issue #11).
                assertTrue(
                    names.contains(file),
                    "${root.name} is missing root entry `$file` (issue #11). Entries:\n" +
                        names.filter {
                            it.contains("pdfium") || it.endsWith(".mjs") || it.endsWith(".wasm")
                        }.joinToString("\n"),
                )
            }
        }
    }
}

private fun artefactFiles(property: String): List<File> {
    val raw = System.getProperty(property)
        ?: fail("system property `$property` is not set — run via :pdfium:packagingTest")
    return raw.split(File.pathSeparator)
        .filter { it.isNotBlank() }
        .map { File(it) }
        .filter { it.exists() }
}

private fun artefactNames(file: File): List<String> = when {
    file.isDirectory -> file.walkTopDown()
        .filter { it.isFile }
        .map { it.relativeTo(file).invariantSeparatorsPath }
        .toList()
    else -> ZipFile(file).use { zip -> zip.entries().toList().map { it.name } }
}

private fun artefactText(file: File, name: String): String = when {
    file.isDirectory -> {
        val child = file.resolve(name)
        if (!child.isFile) fail("${file.name} has no `$name`")
        child.readText()
    }
    else -> ZipFile(file).use { zip ->
        val entry = zip.getEntry(name) ?: fail("${file.name} has no `$name`")
        zip.getInputStream(entry).bufferedReader().readText()
    }
}
