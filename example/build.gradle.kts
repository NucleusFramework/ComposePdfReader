@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import io.github.kdroidfilter.nucleus.desktop.application.dsl.CompressionLevel
import io.github.kdroidfilter.nucleus.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.js.ir.DefaultIncrementalSyncTask

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    alias(libs.plugins.nucleus)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    jvm()

    js {
        outputModuleName.set("composeApp")
        browser {
            commonWebpackConfig {
                outputFileName = "composeApp.js"
            }
            // Disable karma-based tests: karma is fetched from github.com at NPM-install
            // time and fails on hosts with restricted CA stores. We don't run browser
            // tests for composeApp anyway.
            testTask { enabled = false }
        }
        binaries.executable()
    }

    wasmJs {
        outputModuleName.set("composeApp")
        browser {
            commonWebpackConfig {
                outputFileName = "composeApp.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.activity.compose)
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(projects.pdfium)
            implementation(libs.filekit.core)
            implementation(libs.filekit.dialogs.compose)
            implementation(libs.composeUnstyled)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        webMain.dependencies {
            implementation(libs.kotlinx.browser)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.compose.material3)
            implementation(libs.kotlinx.coroutinesSwing)
            // Nucleus runtime: dark-mode detection, decorated window, GraalVM bootstrap.
            implementation(libs.nucleus.core.runtime)
            implementation(libs.nucleus.darkmode.detector)
            implementation(libs.nucleus.decorated.window.material3)
            implementation(libs.nucleus.decorated.window.jni)
            implementation(libs.nucleus.graalvm.runtime)
        }
    }
}

android {
    namespace = "dev.nucleusframework.pdf"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.nucleusframework.pdf"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    debugImplementation(libs.compose.uiTooling)
}

// The :pdfium module ships pdfium_glue.mjs, pdfium_runtime.mjs (wrapped emscripten JS),
// and pdfium.wasm. We copy the trio flat into composeApp's incremental sync dir (next
// to composeApp.mjs) so `@JsModule("./pdfium_glue.mjs")` resolves against it — the same
// layout kotlin-wasm-examples/browser-c-interop uses for its own .mjs/.wasm pair.
val pdfiumWasmDir = project(":pdfium").layout.projectDirectory.dir("src/webMain/resources/pdfium")

val isProductionWeb = project.hasProperty("isProduction")
    || project.gradle.startParameter.taskNames.any { it.endsWith("Distribution") }

// wasmJs and js each have their own compile-sync task (destructive: it mirrors its
// declared inputs into the webpack package dir and deletes anything else). Register one
// copy task per target that runs `finalizedBy` the sync — that way pdfium_glue (plus
// sibling .mjs/.wasm assets) lands next to composeApp.mjs before webpack resolves
// `@JsModule("./pdfium_glue.mjs")`.
val webTargets = listOf("wasmJs", "js")

webTargets.forEach { target ->
    val syncTaskName = if (isProductionWeb) {
        "${target}ProductionExecutableCompileSync"
    } else {
        "${target}DevelopmentExecutableCompileSync"
    }
    val copyAssets = tasks.register<Copy>("copyPdfium${target.replaceFirstChar { it.uppercase() }}Assets") {
        dependsOn(":pdfium:installPdfiumWasm", ":pdfium:generatePdfiumWasmRuntime")
        from(pdfiumWasmDir) {
            include("pdfium_glue.mjs", "pdfium_runtime.mjs", "pdfium_worker.mjs", "pdfium.wasm")
        }
        into(tasks.named<DefaultIncrementalSyncTask>(syncTaskName).flatMap { it.destinationDirectory })
    }
    tasks.named(syncTaskName) { finalizedBy(copyAssets) }
    tasks.matching {
        it.name in setOf(
            "${target}BrowserDevelopmentWebpack",
            "${target}BrowserProductionWebpack",
            "${target}BrowserDevelopmentRun",
            "${target}BrowserProductionRun",
        )
    }.configureEach { dependsOn(copyAssets) }

    // Webpack bundles pdfium_glue.mjs and pdfium_runtime.mjs into composeApp.js via their
    // ES imports, but pdfium_worker.mjs is loaded at runtime via `new Worker(urlString)` —
    // webpack can't statically see the reference, so it doesn't bundle the worker. Same
    // for pdfium.wasm, which Emscripten fetches at runtime. Both must be served as static
    // files next to the bundle, so we copy them into processedResources (picked up by the
    // dev server and the final dist).
    val copyToResources = tasks.register<Copy>("copyPdfium${target.replaceFirstChar { it.uppercase() }}ToResources") {
        dependsOn(":pdfium:installPdfiumWasm", ":pdfium:generatePdfiumWasmRuntime")
        from(pdfiumWasmDir) { include("pdfium.wasm", "pdfium_worker.mjs", "pdfium_runtime.mjs") }
        into(layout.buildDirectory.dir("processedResources/$target/main"))
    }
    tasks.matching { it.name == "${target}ProcessResources" }.configureEach {
        dependsOn(copyToResources)
    }
}

// Nucleus handles packaging, native distributions and GraalVM native-image.
// It also registers the Compose desktop application block internally, so declaring our own
// `compose.desktop.application { ... }` would duplicate the packaging tasks. Use `hotRun`
// with `-PmainClass=...` if Compose Hot Reload needs the main class.
nucleus.application {
    mainClass = "dev.nucleusframework.pdf.MainKt"


    graalvm {
        isEnabled = true
        javaLanguageVersion = 25
        jvmVendor = JvmVendorSpec.BELLSOFT
        imageName = "nucleus-pdf"
        march = providers.gradleProperty("nativeMarch").getOrElse("native")
        buildArgs.addAll(
            "-H:+AddAllCharsets",
            "-Djava.awt.headless=false",
            "-Os",
            "-H:-IncludeMethodData",
        )
    }

    nativeDistributions {
        cleanupNativeLibs = true
        compressionLevel = CompressionLevel.Maximum
        targetFormats(TargetFormat.Dmg, TargetFormat.Nsis, TargetFormat.Deb, TargetFormat.AppImage)
        packageName = "NucleusPdf"
        packageVersion = "1.0.0"
        homepage = "https://github.com/kdroidFilter/ComposePdf"
        // jdk.security.auth: UnixSystem — required by FileKit's XDG/DBus picker on Linux.
        // java.management: DBus transport dependencies.
        // jdk.unsupported: used by various native-interop helpers.
        modules("jdk.security.auth", "java.management", "jdk.unsupported")
        fileAssociation(
            mimeType = "application/pdf",
            extension = "pdf",
            description = "PDF Document",
        )
        linux {
            debMaintainer = "KDroidFilter <dev@kdroidfilter.com>"
        }
    }
}
