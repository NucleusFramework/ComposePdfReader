package dev.nucleusframework.pdfium.jvm

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

/**
 * Loads the native PDFium core library + our JNI glue. Binaries ship as classpath resources
 * under `/pdfium/native/<platform>/` and are extracted on first use to a user-level cache. If
 * the libraries are already on `java.library.path` (packaged app, native-image), the classpath
 * step is skipped.
 */
internal object PdfiumLibraryLoader {
    private const val RESOURCE_PREFIX = "pdfium/native"
    private val loaded = HashSet<String>()

    @Synchronized
    fun ensureLoaded() {
        if ("pdfiumjni" in loaded) return
        val platform = detectPlatform()
        // Load order matters — pdfiumjni depends on pdfium.
        loadLibrary("pdfium", platform)
        loadLibrary("pdfiumjni", platform)
    }

    private fun loadLibrary(name: String, platform: String) {
        if (name in loaded) return
        try {
            System.loadLibrary(name)
            loaded += name
            return
        } catch (_: UnsatisfiedLinkError) {
            // Fall through to classpath extraction.
        }
        val file = extractFromClasspath(name, platform)
            ?: error("PDFium native library '$name' not found for platform $platform")
        System.load(file.absolutePath)
        loaded += name
    }

    private fun extractFromClasspath(name: String, platform: String): File? {
        val fileName = mapLibraryName(name, platform)
        val resourcePath = "$RESOURCE_PREFIX/$platform/$fileName"
        val url = PdfiumLibraryLoader::class.java.classLoader
            ?.getResource(resourcePath)
            ?: return null

        val cacheDir = resolveCacheDir().resolve(platform).also { it.mkdirs() }
        val target = cacheDir.resolve(fileName)

        val remoteLen = runCatching { url.openConnection().contentLengthLong }.getOrDefault(-1L)
        if (target.exists() && (remoteLen < 0 || target.length() == remoteLen)) return target

        val tmp = File(cacheDir, "$fileName.tmp")
        try {
            url.openStream().use { input ->
                Files.copy(input, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            target.setExecutable(true)
        } finally {
            tmp.delete()
        }
        return target
    }

    private fun resolveCacheDir(): File {
        val home = System.getProperty("user.home") ?: "."
        val os = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
        return when {
            os.contains("win") -> File(System.getenv("LOCALAPPDATA") ?: "$home/AppData/Local", "pdfium/native")
            os.contains("mac") -> File(home, "Library/Caches/pdfium/native")
            else -> File(System.getenv("XDG_CACHE_HOME") ?: "$home/.cache", "pdfium/native")
        }
    }

    private fun detectPlatform(): String {
        val os = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
        val arch = System.getProperty("os.arch").orEmpty().lowercase(Locale.ROOT)
        val isArm64 = arch.contains("aarch64") || arch.contains("arm64")
        return when {
            os.contains("win") -> if (isArm64) "win32-arm64" else "win32-x86-64"
            os.contains("mac") -> if (isArm64) "darwin-aarch64" else "darwin-x86-64"
            else -> if (isArm64) "linux-aarch64" else "linux-x86-64"
        }
    }

    private fun mapLibraryName(name: String, platform: String): String = when {
        platform.startsWith("win32") -> "$name.dll"
        platform.startsWith("darwin") -> "lib$name.dylib"
        else -> "lib$name.so"
    }
}
