@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import de.undercouch.gradle.tasks.download.Download
import java.io.File
import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.CommandLineArgumentProvider
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.downloadTask)
    alias(libs.plugins.vanniktechMavenPublish)
}

// Explicit -PpublishVersion wins (used by the auto-release inline publish, where the
// GITHUB_REF default variable cannot be overridden). Otherwise derive from a release
// tag ref; non-tag refs (refs/heads/…) fall back to the dev version instead of
// producing an invalid version containing '/'.
val publishVersion: String =
    providers.gradleProperty("publishVersion").orNull
        ?: providers.environmentVariable("GITHUB_REF")
            .orNull
            ?.takeIf { it.startsWith("refs/tags/v") }
            ?.removePrefix("refs/tags/v")
        ?: "0.1.0"

group = "dev.nucleusframework"
version = publishVersion

val pdfiumVersion = libs.versions.pdfium.bblanchon.get()
val pdfiumBaseUrl = "https://github.com/bblanchon/pdfium-binaries/releases/download/$pdfiumVersion"
val pdfiumDownloadsDir = layout.buildDirectory.dir("pdfium/downloads")
val pdfiumExtractDir = layout.buildDirectory.dir("pdfium/extracted")

data class PdfiumTriplet(val id: String, val archive: String) : java.io.Serializable

val jvmTriplets: List<PdfiumTriplet> = listOf(
    PdfiumTriplet("linux-x86-64", "pdfium-linux-x64"),
    PdfiumTriplet("linux-aarch64", "pdfium-linux-arm64"),
    PdfiumTriplet("darwin-x86-64", "pdfium-mac-x64"),
    PdfiumTriplet("darwin-aarch64", "pdfium-mac-arm64"),
    PdfiumTriplet("win32-x86-64", "pdfium-win-x64"),
    PdfiumTriplet("win32-arm64", "pdfium-win-arm64"),
)

val androidTriplets: List<Pair<String, String>> = listOf(
    "arm64-v8a" to "pdfium-android-arm64",
    "armeabi-v7a" to "pdfium-android-arm",
    "x86_64" to "pdfium-android-x64",
    "x86" to "pdfium-android-x86",
)

// iOS needs a *static* libpdfium so cinterop can pack it into the klib and Maven
// consumers link without any -L or embedded framework (issue #11). bblanchon ships
// Apple platforms as a dylib only, so NucleusFramework/pdfium-binaries (a fork of
// their build harness) runs the same steps with `build_type=static` and publishes
// the archives per chromium build.
val pdfiumIosBuild = pdfiumVersion.substringAfterLast('/')
val pdfiumIosArchive = "pdfium-ios-static-$pdfiumIosBuild"
val pdfiumIosUrl = "https://github.com/NucleusFramework/pdfium-binaries/releases/download/" +
    "ios-static-$pdfiumIosBuild/$pdfiumIosArchive.tgz"

val iosTriplets: List<String> = listOf("ios-arm64", "ios-simulator-arm64")

val wasmArchive = "pdfium-wasm"

val allArchives: Set<String> =
    (jvmTriplets.map { it.archive } +
        androidTriplets.map { it.second } +
        listOf(wasmArchive)).toSet()

val downloadTasks: Map<String, TaskProvider<Download>> = allArchives.associateWith { archive ->
    tasks.register<Download>("downloadPdfium_$archive") {
        src("$pdfiumBaseUrl/$archive.tgz")
        dest(pdfiumDownloadsDir.map { it.file("$archive.tgz") })
        overwrite(false)
        onlyIfModified(true)
        retries(2)
    }
}

val extractTasks: Map<String, TaskProvider<Copy>> = allArchives.associateWith { archive ->
    val dl = downloadTasks.getValue(archive)
    tasks.register<Copy>("extractPdfium_$archive") {
        dependsOn(dl)
        from({ tarTree(resources.gzip(dl.get().dest)) })
        into(pdfiumExtractDir.map { it.dir(archive) })
    }
}

val downloadPdfiumIos = tasks.register<Download>("downloadPdfiumIos") {
    src(pdfiumIosUrl)
    dest(pdfiumDownloadsDir.map { it.file("$pdfiumIosArchive.tgz") })
    overwrite(false)
    onlyIfModified(true)
    retries(2)
}

// The archive unpacks to `<pdfiumIosArchive>/{include,lib/<triplet>}`.
val extractPdfiumIos = tasks.register<Copy>("extractPdfiumIos") {
    dependsOn(downloadPdfiumIos)
    from({ tarTree(resources.gzip(downloadPdfiumIos.get().dest)) })
    into(pdfiumExtractDir.map { it.dir("ios-static") })
}

kotlin {
    jvmToolchain(17)

    androidTarget {
        publishLibraryVariants("release")
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
    }

    jvm()

    js {
        // ES-module output so a single `@file:JsModule("./pdfium_glue.mjs")` declaration
        // (shared with wasmJs in webMain) works without a jsMain-only `@JsNonModule`
        // companion annotation.
        useEsModules()
        browser {
            // Karma pulls from github.com and fails SSL on some hosts; we run no JS tests.
            testTask { enabled = false }
        }
    }

    wasmJs {
        browser()
        compilerOptions {
            optIn.add("kotlin.js.ExperimentalWasmJsInterop")
        }
    }

    // iOS targets are cross-compilable from any host (Kotlin/Native ships the
    // necessary toolchain). The cinterop only needs the staged PDFium headers —
    // libraryPaths/linkerOpts are consumed at final link time on macOS only.
    val iosTargets = listOf(iosArm64(), iosSimulatorArm64())
    iosTargets.forEach { target ->
        target.compilations.getByName("main") {
            cinterops.create("pdfium") {
                defFile(project.file("src/nativeInterop/cinterop/pdfium.def"))
                packageName("dev.nucleusframework.pdfium.native")
            }
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.okio)
        }
        androidMain.dependencies { implementation(libs.kotlinx.coroutinesAndroid) }
        jvmMain.dependencies { implementation(libs.kotlinx.coroutinesSwing) }
        // kotlinx-browser exposes the org.khronos.webgl.* typed arrays as a shared API
        // across js + wasmJs, so they resolve in the webMain metadata compilation.
        webMain.dependencies { implementation(libs.kotlinx.browser) }
        jvmTest.dependencies { implementation(libs.kotlin.test) }
    }
}

android {
    namespace = "dev.nucleusframework.pdfium"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86") }
        externalNativeBuild {
            cmake { cppFlags += listOf("-std=c++17", "-fvisibility=hidden") }
        }
    }

    sourceSets {
        getByName("main") { jniLibs.srcDirs("src/androidMain/jniLibs") }
    }

    externalNativeBuild {
        cmake {
            path = file("src/androidMain/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// ---------- Install tasks (configuration-cache compatible) ----------

abstract class InstallJvmResourcesTask : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection
    @get:Input abstract val triplets: ListProperty<PdfiumTriplet>
    @get:Input abstract val archiveToDir: org.gradle.api.provider.MapProperty<String, String>
    @get:OutputDirectory abstract val outputRoot: DirectoryProperty

    @TaskAction
    fun run() {
        val root = outputRoot.get().asFile
        val mapping = archiveToDir.get()
        triplets.get().forEach { t ->
            val archiveRootPath = mapping[t.archive] ?: return@forEach
            val archiveRoot = File(archiveRootPath)
            val targetDir = root.resolve(t.id).apply { mkdirs() }

            // Source directories for this platform's runtime/link artifacts.
            val searchDirs = if (t.id.startsWith("win32")) {
                // Windows ships pdfium.dll under bin/ and pdfium.dll.lib under lib/.
                listOf(archiveRoot.resolve("bin"), archiveRoot.resolve("lib"))
            } else {
                listOf(archiveRoot.resolve("lib"))
            }

            var staged = 0
            searchDirs.filter { it.exists() }.forEach { dir ->
                dir.listFiles()?.forEach { src ->
                    val keep = when {
                        t.id.startsWith("win32") -> src.name.endsWith(".dll") || src.name.endsWith(".dll.lib")
                        t.id.startsWith("darwin") -> src.name.endsWith(".dylib")
                        t.id.startsWith("linux") -> src.name.contains(".so")
                        else -> false
                    }
                    if (keep) {
                        src.copyTo(targetDir.resolve(src.name), overwrite = true)
                        staged++
                    }
                }
            }
            if (staged == 0) logger.warn("pdfium: no matching libs for ${t.archive} — skipping")
        }
    }
}

abstract class InstallAndroidJniTask : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection
    @get:Input abstract val triplets: ListProperty<String> // "abi|archive"
    @get:Input abstract val archiveToDir: org.gradle.api.provider.MapProperty<String, String>
    @get:OutputDirectory abstract val outputRoot: DirectoryProperty

    @TaskAction
    fun run() {
        val root = outputRoot.get().asFile
        val mapping = archiveToDir.get()
        triplets.get().forEach { pair ->
            val (abi, archive) = pair.split('|', limit = 2)
            val libDirPath = mapping[archive] ?: return@forEach
            val libDir = File("$libDirPath/lib")
            val so = libDir.listFiles()?.firstOrNull {
                it.name.startsWith("libpdfium") && it.name.endsWith(".so")
            }
            if (so == null) {
                logger.warn("pdfium: libpdfium.so missing in $archive")
                return@forEach
            }
            val dir = root.resolve(abi).apply { mkdirs() }
            so.copyTo(dir.resolve("libpdfium.so"), overwrite = true)
        }
    }
}

abstract class InstallHeadersTask : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val out = outputDir.get().asFile.apply { mkdirs() }
        // sources points to the extracted archive root; copy its include/.
        sources.forEach { root ->
            val includeDir = root.resolve("include")
            if (includeDir.exists()) includeDir.copyRecursively(out, overwrite = true)
        }
    }
}

/**
 * Stages one static `libpdfium.a` per Konan target plus the matching public
 * headers. cinterop then packs the archive into the klib, so the published
 * library carries the iOS machine code and consumers need no linker
 * configuration at all (issue #11).
 */
abstract class InstallIosTask : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection
    @get:Input abstract val releaseRoot: Property<String>
    @get:Input abstract val triplets: ListProperty<String>
    @get:OutputDirectory abstract val outputRoot: DirectoryProperty
    @get:OutputDirectory abstract val headersDir: DirectoryProperty

    @TaskAction
    fun run() {
        val release = File(releaseRoot.get())
        val root = outputRoot.get().asFile
        triplets.get().forEach { triplet ->
            val archive = release.resolve("lib/$triplet/libpdfium.a")
            require(archive.exists()) { "pdfium: ${archive.absolutePath} missing" }
            val target = root.resolve(triplet)
            target.deleteRecursively()
            target.mkdirs()
            archive.copyTo(target.resolve("libpdfium.a"), overwrite = true)
        }
        // Bind the cinterop to the headers the staged archives were built from,
        // so no stub can reference a symbol the archive doesn't define.
        val include = release.resolve("include")
        require(include.isDirectory) { "pdfium: ${include.absolutePath} missing" }
        include.copyRecursively(headersDir.get().asFile.apply { mkdirs() }, overwrite = true)
    }
}

val nativeJniResourceDir = layout.projectDirectory.dir("src/jvmMain/resources/pdfium/native")
val androidJniLibsDir = layout.projectDirectory.dir("src/androidMain/jniLibs")
val iosStaticLibsDir = layout.projectDirectory.dir("src/nativeInterop/libs")
val iosHeadersDir = layout.buildDirectory.dir("pdfium/ios-include")
// Flat resource root so webpack/dev-server resolve `./pdfium_glue.mjs` and
// `pdfium_worker.mjs` from the bundle root (issue #11). Nested `pdfium/` made
// the files invisible to `@JsModule("./pdfium_glue.mjs")`.
val wasmResourceDir = layout.projectDirectory.dir("src/webMain/resources")
val stagedHeadersDir = layout.buildDirectory.dir("pdfium/include")

fun extractedDir(archive: String) = pdfiumExtractDir.map { it.dir(archive) }

val installPdfiumJvmResources = tasks.register<InstallJvmResourcesTask>("installPdfiumJvmResources") {
    group = "pdfium"
    description = "Stage bblanchon PDFium shared libs as JVM classpath resources."
    jvmTriplets.forEach { t ->
        val extract = extractTasks.getValue(t.archive)
        sources.from(extract.map { it.outputs.files })
    }
    triplets.set(jvmTriplets)
    archiveToDir.set(jvmTriplets.associate { it.archive to pdfiumExtractDir.get().dir(it.archive).asFile.absolutePath })
    outputRoot.set(nativeJniResourceDir)
}

val installPdfiumAndroidJniLibs = tasks.register<InstallAndroidJniTask>("installPdfiumAndroidJniLibs") {
    group = "pdfium"
    description = "Install PDFium .so into Android jniLibs."
    androidTriplets.forEach { (_, archive) ->
        sources.from(extractTasks.getValue(archive).map { it.outputs.files })
    }
    triplets.set(androidTriplets.map { "${it.first}|${it.second}" })
    archiveToDir.set(androidTriplets.associate { it.second to pdfiumExtractDir.get().dir(it.second).asFile.absolutePath })
    outputRoot.set(androidJniLibsDir)
}

val installPdfiumHeaders = tasks.register<InstallHeadersTask>("installPdfiumHeaders") {
    group = "pdfium"
    description = "Stage PDFium public headers for CMake/cinterop consumers."
    // linux-x64 is always downloaded; its include/ is identical to every other archive's include/.
    sources.from(extractTasks.getValue("pdfium-linux-x64").map { it.outputs.files })
    outputDir.set(stagedHeadersDir)
}

val installPdfiumWasm = tasks.register<Copy>("installPdfiumWasm") {
    group = "pdfium"
    description = "Stage pdfium.wasm into the wasmJs resources directory."
    dependsOn(extractTasks.getValue(wasmArchive))
    from(pdfiumExtractDir.map { it.dir(wasmArchive).dir("lib") }) {
        include("pdfium.wasm")
    }
    into(wasmResourceDir)
}

// Emscripten's classic JS glue expects to run as a top-level <script>. To consume it
// from an ES module without injecting a <script> tag (which browsers refuse to load
// from file:// and which breaks the clean import graph of the example), we embed the
// JS source verbatim into `pdfium_runtime.mjs` and run it via indirect eval — this
// executes in global scope, so the glue's top-level `var Module = …` resolves to
// `globalThis.Module`, preserving our preconfigured object.
val generatePdfiumWasmRuntime = tasks.register("generatePdfiumWasmRuntime") {
    group = "pdfium"
    description = "Wrap bblanchon's pdfium.js into an ES module (pdfium_runtime.mjs)."
    dependsOn(extractTasks.getValue(wasmArchive))
    val pdfiumJs = pdfiumExtractDir.map { it.dir(wasmArchive).dir("lib").file("pdfium.js") }
    val outputMjs = wasmResourceDir.file("pdfium_runtime.mjs")
    inputs.file(pdfiumJs)
    outputs.file(outputMjs)
    doLast {
        val jsText = pdfiumJs.get().asFile.readText()
        val escaped = buildString(jsText.length + 64) {
            append('"')
            for (c in jsText) when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                in '\u0000'..'\u001F' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
                '\u2028' -> append("\\u2028")
                '\u2029' -> append("\\u2029")
                else -> append(c)
            }
            append('"')
        }
        outputMjs.asFile.writeText(
            """
            |// Auto-generated — do not edit. Embeds bblanchon's emscripten-built pdfium.js as
            |// an ES module factory. See :pdfium:generatePdfiumWasmRuntime.
            |
            |const PDFIUM_JS_SOURCE = $escaped;
            |
            |export function initPdfium(config) {
            |    return new Promise((resolve, reject) => {
            |        const Module = Object.assign({}, config, {
            |            onRuntimeInitialized() { resolve(Module); },
            |            onAbort(reason) { reject(new Error('pdfium aborted: ' + reason)); },
            |        });
            |        globalThis.Module = Module;
            |        (0, eval)(PDFIUM_JS_SOURCE);
            |    });
            |}
            |
            """.trimMargin()
        )
    }
}

abstract class GeneratePdfiumGlueSourceTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val glueMjs: RegularFileProperty

    @get:org.gradle.api.tasks.OutputFile
    abstract val outputKt: RegularFileProperty

    @TaskAction
    fun run() {
        val jsText = glueMjs.get().asFile.readText()
        val escaped = buildString(jsText.length + 64) {
            append('"')
            for (c in jsText) when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                in '\u0000'..'\u001F' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
                '\u2028' -> append("\\u2028")
                '\u2029' -> append("\\u2029")
                else -> append(c)
            }
            append('"')
        }
        val out = outputKt.get().asFile
        out.parentFile.mkdirs()
        out.writeText(
            """
            |package dev.nucleusframework.pdfium
            |
            |internal const val PDFIUM_GLUE_JS: String = $escaped
            |
            """.trimMargin(),
        )
    }
}

val pdfiumGlueGeneratedDir = layout.buildDirectory.dir("generated/pdfiumGlue/kotlin")
val generatePdfiumGlueSource = tasks.register<GeneratePdfiumGlueSourceTask>("generatePdfiumGlueSource") {
    group = "pdfium"
    description = "Embed pdfium_glue.mjs as a Kotlin string so web targets eval it without webpack (issue #11)."
    glueMjs.set(layout.projectDirectory.file("src/webMain/resources/pdfium_glue.mjs"))
    outputKt.set(pdfiumGlueGeneratedDir.map { it.file("dev/nucleusframework/pdfium/PdfiumGlueSource.kt") })
    mustRunAfter(installPdfiumWasm, generatePdfiumWasmRuntime)
}

kotlin.sourceSets.getByName("webMain").kotlin.srcDir(pdfiumGlueGeneratedDir)
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    if ("Js" in name || "Wasm" in name || "Web" in name || "Metadata" in name) {
        dependsOn(generatePdfiumGlueSource)
    }
}

val installPdfiumIos = tasks.register<InstallIosTask>("installPdfiumIos") {
    group = "pdfium"
    description = "Stage the static PDFium archives + headers for the iOS cinterop."
    sources.from(extractPdfiumIos.map { it.outputs.files })
    releaseRoot.set(pdfiumExtractDir.get().dir("ios-static/$pdfiumIosArchive").asFile.absolutePath)
    triplets.set(iosTriplets)
    outputRoot.set(iosStaticLibsDir)
    headersDir.set(iosHeadersDir)
}

// ---------- JNI glue compilation ----------

val buildJniLinux = tasks.register<Exec>("buildJniLinux") {
    group = "pdfium"
    description = "Compile the JNI glue for Linux (host architecture — x86_64 or aarch64)."
    onlyIf { Os.isFamily(Os.FAMILY_UNIX) && !Os.isFamily(Os.FAMILY_MAC) }
    dependsOn(installPdfiumJvmResources, installPdfiumHeaders)
    val scriptDir = layout.projectDirectory.dir("src/jvmMain/native")
    workingDir(scriptDir)
    commandLine("bash", "build-linux.sh")
    val hostTriplet = when (System.getProperty("os.arch")) {
        "aarch64", "arm64" -> "linux-aarch64"
        else -> "linux-x86-64"
    }
    environment("PDFIUM_INCLUDE", stagedHeadersDir.get().asFile.absolutePath)
    environment("PDFIUM_LIB", nativeJniResourceDir.dir(hostTriplet).asFile.absolutePath)
    environment("OUT_DIR", nativeJniResourceDir.dir(hostTriplet).asFile.absolutePath)
}

val buildJniMacOs = tasks.register<Exec>("buildJniMacOs") {
    group = "pdfium"
    description = "Compile the JNI glue for macOS (arm64 + x86_64)."
    onlyIf { Os.isFamily(Os.FAMILY_MAC) }
    dependsOn(installPdfiumJvmResources, installPdfiumHeaders)
    val scriptDir = layout.projectDirectory.dir("src/jvmMain/native")
    workingDir(scriptDir)
    commandLine("bash", "build-macos.sh")
    environment("PDFIUM_INCLUDE", stagedHeadersDir.get().asFile.absolutePath)
    environment("PDFIUM_LIB_ARM64", nativeJniResourceDir.dir("darwin-aarch64").asFile.absolutePath)
    environment("PDFIUM_LIB_X64", nativeJniResourceDir.dir("darwin-x86-64").asFile.absolutePath)
    environment("OUT_DIR_ARM64", nativeJniResourceDir.dir("darwin-aarch64").asFile.absolutePath)
    environment("OUT_DIR_X64", nativeJniResourceDir.dir("darwin-x86-64").asFile.absolutePath)
}

val buildJniWindows = tasks.register<Exec>("buildJniWindows") {
    group = "pdfium"
    description = "Compile the JNI glue for Windows x64. Auto-detects MSVC via vswhere if not on PATH."
    onlyIf { Os.isFamily(Os.FAMILY_WINDOWS) }
    dependsOn(installPdfiumJvmResources, installPdfiumHeaders)
    val scriptDir = layout.projectDirectory.dir("src/jvmMain/native")
    workingDir(scriptDir)
    commandLine("cmd", "/c", scriptDir.file("build-windows.bat").asFile.absolutePath, "x64")
    environment("PDFIUM_INCLUDE", stagedHeadersDir.get().asFile.absolutePath)
    environment("PDFIUM_LIB", nativeJniResourceDir.dir("win32-x86-64").asFile.absolutePath)
    environment("OUT_DIR", nativeJniResourceDir.dir("win32-x86-64").asFile.absolutePath)
}

val buildJniWindowsArm = tasks.register<Exec>("buildJniWindowsArm") {
    group = "pdfium"
    description = "Compile the JNI glue for Windows arm64. Requires the MSVC ARM64 cross-compiler. Opt in with -Ppdfium.buildWinArm=true."
    val buildWinArm = providers.gradleProperty("pdfium.buildWinArm").orNull == "true" ||
        System.getProperty("os.arch").equals("aarch64", ignoreCase = true)
    onlyIf { Os.isFamily(Os.FAMILY_WINDOWS) && buildWinArm }
    dependsOn(installPdfiumJvmResources, installPdfiumHeaders)
    val scriptDir = layout.projectDirectory.dir("src/jvmMain/native")
    workingDir(scriptDir)
    commandLine("cmd", "/c", scriptDir.file("build-windows.bat").asFile.absolutePath, "arm64")
    environment("PDFIUM_INCLUDE", stagedHeadersDir.get().asFile.absolutePath)
    environment("PDFIUM_LIB", nativeJniResourceDir.dir("win32-arm64").asFile.absolutePath)
    environment("OUT_DIR", nativeJniResourceDir.dir("win32-arm64").asFile.absolutePath)
}

tasks.named("jvmProcessResources") {
    dependsOn(installPdfiumJvmResources, buildJniLinux, buildJniMacOs, buildJniWindows, buildJniWindowsArm)
}

// Both wasmJs and js source sets read staged pdfium.wasm + runtime glue from
// webMain resources; wire the install/generate tasks as explicit deps of every
// processResources task that copies from that directory. The commonized
// metadataWebMainProcessResources (run during publication) also reads the same
// directory, so it needs the same dependency.
tasks.matching {
    it.name == "wasmJsProcessResources" ||
        it.name == "jsProcessResources" ||
        it.name == "metadataWebMainProcessResources"
}.configureEach {
    dependsOn(installPdfiumWasm, generatePdfiumWasmRuntime)
}

tasks.matching { it.name == "preBuild" || it.name == "preDebugBuild" || it.name == "preReleaseBuild" }.configureEach {
    dependsOn(installPdfiumAndroidJniLibs, installPdfiumHeaders)
}

tasks.matching { it.name.startsWith("cinteropPdfium") }.configureEach {
    dependsOn(installPdfiumIos)
}

// ---------- Packaged-binaries regression (issue #11) ----------

abstract class PackagedBinariesArgumentProvider : CommandLineArgumentProvider {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val iosKlibs: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val webRoots: ConfigurableFileCollection

    @get:Input
    abstract val iosSupported: Property<Boolean>

    override fun asArguments(): MutableIterable<String> = mutableListOf(
        "-Dpdfium.ios.supported=${iosSupported.get()}",
        "-Dpdfium.ios.cinterop.klibs=${iosKlibs.files.joinToString(File.pathSeparator)}",
        "-Dpdfium.web.klibs=${webRoots.files.joinToString(File.pathSeparator)}",
    )
}

tasks.named<Test>("jvmTest") {
    filter {
        excludeTestsMatching("dev.nucleusframework.pdfium.PackagedBinariesTest")
    }
}

// The klibs Maven consumers receive are the packed ones in build/libs, produced
// by the `…Cinterop-pdfiumKlib` tasks — not by cinterop itself, which only writes
// the unpacked directory under build/classes. Assert against the former.
val iosCinteropKlibTaskNames = listOf(
    "iosArm64Cinterop-pdfiumKlib",
    "iosSimulatorArm64Cinterop-pdfiumKlib",
)

// KGP disables cross compilation for targets that declare cinterops, so the iOS
// klibs simply don't exist off a macOS host. The web half still runs everywhere;
// the iOS half is covered by the `packaging-ios` CI job and by the publish run.
val iosPackagingSupported = Os.isFamily(Os.FAMILY_MAC)

tasks.register<Test>("packagingTest") {
    group = "verification"
    description = "Assert iOS/web PDFium binaries are packaged into published klibs (issue #11)."
    val jvmTest = tasks.named<Test>("jvmTest")
    testClassesDirs = jvmTest.map { it.testClassesDirs }.get()
    classpath = jvmTest.map { it.classpath }.get()
    filter { includeTestsMatching("dev.nucleusframework.pdfium.PackagedBinariesTest") }

    if (iosPackagingSupported) iosCinteropKlibTaskNames.forEach { dependsOn(it) }
    dependsOn("wasmJsProcessResources", "jsProcessResources")

    val args = objects.newInstance<PackagedBinariesArgumentProvider>()
    args.iosSupported.set(iosPackagingSupported)
    if (iosPackagingSupported) {
        args.iosKlibs.from(
            layout.buildDirectory.dir("libs").map { dir ->
                dir.asFileTree.matching { include("*Cinterop*.klib") }
            },
        )
        args.iosKlibs.builtBy(iosCinteropKlibTaskNames.map { tasks.named(it) })
    }
    args.webRoots.from(layout.buildDirectory.dir("processedResources/wasmJs/main"))
    args.webRoots.from(layout.buildDirectory.dir("processedResources/js/main"))
    args.webRoots.builtBy("wasmJsProcessResources", "jsProcessResources")
    jvmArgumentProviders.add(args)
}

tasks.named("check") { dependsOn("packagingTest") }

// ---------- Smoke test ----------

val smokeTestRuntime by configurations.creating {
    extendsFrom(configurations.getByName("jvmRuntimeClasspath"))
}
dependencies { smokeTestRuntime(compose.desktop.currentOs) }

tasks.register<JavaExec>("smokeTest") {
    group = "verification"
    description = "Open a PDF and render page 0 using the JVM PDFium stack (Linux smoke test)."
    dependsOn(tasks.named("jvmMainClasses"), tasks.named("jvmProcessResources"))
    val main = kotlin.targets.getByName("jvm").compilations.getByName("main")
    classpath = files(main.output.allOutputs) + smokeTestRuntime
    mainClass.set("dev.nucleusframework.pdfium.jvm.SmokeTestKt")
    if (project.hasProperty("pdfPath")) args(project.property("pdfPath") as String)
}

// ---------- Maven Central publication ----------

mavenPublishing {
    coordinates("dev.nucleusframework", "pdfium", publishVersion)

    pom {
        name.set("Nucleus PDF — PDFium")
        description.set(
            "Compose Multiplatform PDF rendering backed by bblanchon's PDFium binaries " +
                "(JVM, Android, iOS, JS, WasmJs).",
        )
        url.set("https://github.com/kdroidFilter/pdf")
        inceptionYear.set("2025")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }

        developers {
            developer {
                id.set("kdroidfilter")
                name.set("kdroidFilter")
                url.set("https://github.com/kdroidFilter")
            }
        }

        scm {
            url.set("https://github.com/kdroidFilter/pdf")
            connection.set("scm:git:git://github.com/kdroidFilter/pdf.git")
            developerConnection.set("scm:git:ssh://git@github.com/kdroidFilter/pdf.git")
        }
    }

    publishToMavenCentral()
    if (project.hasProperty("signingInMemoryKey")) {
        signAllPublications()
    }
}

// Ensure every Jar-producing task sees the staged natives + headers before archiving,
// otherwise the published JVM jar and source jars ship without the bblanchon libs.
tasks.matching { it.name == "sourcesJar" || it.name == "jvmSourcesJar" }.configureEach {
    dependsOn(installPdfiumJvmResources, installPdfiumHeaders)
}
