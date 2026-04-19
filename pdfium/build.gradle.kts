import de.undercouch.gradle.tasks.download.Download
import java.io.File
import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.downloadTask)
}

group = "dev.nucleusframework.pdf"
version = "0.1.0"

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

val iosTriplets: List<Pair<String, String>> = listOf(
    "ios-arm64" to "pdfium-ios-device-arm64",
    "ios-simulator-arm64" to "pdfium-ios-simulator-arm64",
)

val allArchives: Set<String> =
    (jvmTriplets.map { it.archive } +
        androidTriplets.map { it.second } +
        iosTriplets.map { it.second }).toSet()

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

kotlin {
    jvmToolchain(17)

    androidTarget {
        publishLibraryVariants("release")
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
    }

    jvm()

    // iOS targets are always declared so downstream KMP modules can resolve them on any host.
    // On non-Mac hosts the actual compilation is disabled via kotlin.native.ignoreDisabledTargets.
    val iosTargets = listOf(iosArm64(), iosSimulatorArm64())

    if (Os.isFamily(Os.FAMILY_MAC)) {
        iosTargets.forEach { target ->
            target.compilations.getByName("main") {
                cinterops.create("pdfium") {
                    defFile(project.file("src/nativeInterop/cinterop/pdfium.def"))
                    packageName("dev.nucleusframework.pdfium.native")
                }
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

abstract class EmbedPdfiumDylibTask : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val stagedLibsRoot: DirectoryProperty

    @get:Input abstract val platformName: Property<String>
    @get:Input abstract val targetBuildDir: Property<String>
    @get:Input abstract val frameworksFolderPath: Property<String>
    @get:Input @get:Optional abstract val signIdentity: Property<String>
    @get:Input @get:Optional abstract val signingRequired: Property<String>

    @get:javax.inject.Inject abstract val execOps: org.gradle.process.ExecOperations

    @TaskAction
    fun run() {
        val platform = platformName.get()
        require(platform.isNotBlank()) { "PLATFORM_NAME not set — run this task from Xcode." }
        val triplet = when {
            platform.startsWith("iphonesimulator") -> "ios-simulator-arm64"
            platform.startsWith("iphoneos") -> "ios-arm64"
            else -> error("Unsupported PLATFORM_NAME: '$platform'")
        }
        val dylib = stagedLibsRoot.get().dir(triplet).file("libpdfium.dylib").asFile
        require(dylib.exists()) { "libpdfium.dylib missing at ${dylib.absolutePath}" }

        val buildDir = targetBuildDir.get()
        val frameworks = frameworksFolderPath.get()
        require(buildDir.isNotBlank() && frameworks.isNotBlank()) {
            "TARGET_BUILD_DIR / FRAMEWORKS_FOLDER_PATH must be set by Xcode."
        }
        val dest = File("$buildDir/$frameworks").apply { mkdirs() }.resolve(dylib.name)
        dylib.copyTo(dest, overwrite = true)

        if (signingRequired.orNull != "NO") {
            val identity = signIdentity.orNull?.takeIf { it.isNotBlank() } ?: "-"
            execOps.exec {
                commandLine("codesign", "--force", "--sign", identity, dest.absolutePath)
            }
        }
    }
}

abstract class InstallIosTask : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection
    @get:Input abstract val triplets: ListProperty<String> // "triplet|archive"
    @get:Input abstract val archiveToDir: org.gradle.api.provider.MapProperty<String, String>
    @get:OutputDirectory abstract val outputRoot: DirectoryProperty

    @get:javax.inject.Inject abstract val execOps: org.gradle.process.ExecOperations

    @TaskAction
    fun run() {
        val root = outputRoot.get().asFile
        val mapping = archiveToDir.get()
        triplets.get().forEach { pair ->
            val (triplet, archive) = pair.split('|', limit = 2)
            val libDirPath = mapping[archive] ?: return@forEach
            val libDir = File("$libDirPath/lib")
            val target = root.resolve(triplet).apply { mkdirs() }
            libDir.listFiles()?.forEach { src ->
                if (src.name.endsWith(".dylib")) {
                    val dst = target.resolve(src.name)
                    src.copyTo(dst, overwrite = true)
                    // bblanchon ships the dylib with install_name "./libpdfium.dylib".
                    // Rewrite to @rpath so consumer apps can embed it under Frameworks/.
                    execOps.exec {
                        commandLine("install_name_tool", "-id", "@rpath/${src.name}", dst.absolutePath)
                    }
                }
            }
        }
    }
}

val nativeJniResourceDir = layout.projectDirectory.dir("src/jvmMain/resources/pdfium/native")
val androidJniLibsDir = layout.projectDirectory.dir("src/androidMain/jniLibs")
val iosStaticLibsDir = layout.projectDirectory.dir("src/nativeInterop/libs")
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

val installPdfiumIos = tasks.register<InstallIosTask>("installPdfiumIos") {
    group = "pdfium"
    description = "Install iOS PDFium dynamic libs for cinterop."
    iosTriplets.forEach { (_, archive) ->
        sources.from(extractTasks.getValue(archive).map { it.outputs.files })
    }
    dependsOn(installPdfiumHeaders)
    triplets.set(iosTriplets.map { "${it.first}|${it.second}" })
    archiveToDir.set(iosTriplets.associate { it.second to pdfiumExtractDir.get().dir(it.second).asFile.absolutePath })
    outputRoot.set(iosStaticLibsDir)
}

val embedPdfiumDylibForXcode = tasks.register<EmbedPdfiumDylibTask>("embedPdfiumDylibForXcode") {
    group = "pdfium"
    description = "Copy & sign libpdfium.dylib into the iOS app bundle. Invoked from Xcode build phase."
    dependsOn(installPdfiumIos)
    outputs.upToDateWhen { false }

    stagedLibsRoot.set(iosStaticLibsDir)
    platformName.set(providers.environmentVariable("PLATFORM_NAME").orElse(""))
    targetBuildDir.set(providers.environmentVariable("TARGET_BUILD_DIR").orElse(""))
    frameworksFolderPath.set(providers.environmentVariable("FRAMEWORKS_FOLDER_PATH").orElse(""))
    signIdentity.set(providers.environmentVariable("EXPANDED_CODE_SIGN_IDENTITY").orElse(""))
    signingRequired.set(providers.environmentVariable("CODE_SIGNING_REQUIRED").orElse(""))
}

// ---------- JNI glue compilation ----------

val buildJniLinux = tasks.register<Exec>("buildJniLinux") {
    group = "pdfium"
    description = "Compile the JNI glue for Linux x86_64."
    onlyIf { Os.isFamily(Os.FAMILY_UNIX) && !Os.isFamily(Os.FAMILY_MAC) }
    dependsOn(installPdfiumJvmResources, installPdfiumHeaders)
    val scriptDir = layout.projectDirectory.dir("src/jvmMain/native")
    workingDir(scriptDir)
    commandLine("bash", "build-linux.sh")
    environment("PDFIUM_INCLUDE", stagedHeadersDir.get().asFile.absolutePath)
    environment("PDFIUM_LIB", nativeJniResourceDir.dir("linux-x86-64").asFile.absolutePath)
    environment("OUT_DIR", nativeJniResourceDir.dir("linux-x86-64").asFile.absolutePath)
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
    onlyIf {
        Os.isFamily(Os.FAMILY_WINDOWS) &&
            (providers.gradleProperty("pdfium.buildWinArm").orNull == "true" ||
                System.getProperty("os.arch").equals("aarch64", ignoreCase = true))
    }
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

tasks.matching { it.name == "preBuild" || it.name == "preDebugBuild" || it.name == "preReleaseBuild" }.configureEach {
    dependsOn(installPdfiumAndroidJniLibs, installPdfiumHeaders)
}

if (Os.isFamily(Os.FAMILY_MAC)) {
    tasks.matching { it.name.startsWith("cinteropPdfium") }.configureEach {
        dependsOn(installPdfiumIos)
    }
}

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
