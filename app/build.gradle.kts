plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

fun envOrProp(name: String, default: String): String {
    val prop = project.findProperty(name) as String?
    if (!prop.isNullOrBlank()) return prop
    val env = System.getenv(name)
    if (!env.isNullOrBlank()) return env
    return default
}

android {
    namespace = "com.larp.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.larp.launcher"
        minSdk = 26
        targetSdk = 35
        versionCode = envOrProp("LARP_VERSION_CODE", "1").toIntOrNull() ?: 1
        versionName = envOrProp("LARP_VERSION_NAME", "0.1.0")
        vectorDrawables.useSupportLibrary = true

        buildConfigField("String", "APP_VERSION_NAME", "\"${envOrProp("LARP_VERSION_NAME", "0.1.0")}\"")
        buildConfigField("String", "GIT_SHA", "\"${envOrProp("LARP_GIT_SHA", "local")}\"")
        buildConfigField("long", "BUILD_TIMESTAMP", System.currentTimeMillis().toString())
        buildConfigField("boolean", "FRIDA_GADGET_BUNDLED", "true")

        resValue("string", "larp_host", envOrProp("LARP_API_HOST", "larp-api.workers.dev"))

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    flavorDimensions += "channel"
    productFlavors {
        create("stable") { dimension = "channel" }
        create("preview") {
            dimension = "channel"
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
        }
        create("canary") {
            dimension = "channel"
            applicationIdSuffix = ".canary"
            versionNameSuffix = "-canary"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = listOf(
            "-Xjvm-default=all",
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
            assets.srcDirs("src/main/assets")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
            pickFirsts += setOf(
                "lib/*/libcrypto.so",
                "lib/*/libssl.so",
                "lib/*/libcoreclr.so",
                "lib/*/libclrjit.so"
            )
        }
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/INDEX.LIST",
            "/META-INF/io.netty.versions.properties",
            "/META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*"
        )
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

val larpFridaVersion: String = envOrProp("larpFridaVersion", "17.2.1")

abstract class DownloadFridaGadget : org.gradle.api.DefaultTask() {
    @get:org.gradle.api.tasks.Input
    abstract val fridaVersion: org.gradle.api.provider.Property<String>

    @get:org.gradle.api.tasks.OutputDirectory
    abstract val jniDir: org.gradle.api.property.DirectoryProperty

    @org.gradle.api.tasks.TaskAction
    fun run() {
        val version = fridaVersion.get()
        val archs = listOf("arm64-v8a")
        val dir = jniDir.get().asFile
        dir.mkdirs()
        archs.forEach { arch ->
            val target = java.io.File(java.io.File(dir, arch), "libfrida-gadget.so")
            if (target.isFile && target.length() > 1_000_000) {
                logger.lifecycle("[frida] $arch já existe (${target.length() / 1024} KB)")
                return@forEach
            }
            target.parentFile.mkdirs()
            val fridaArch = when (arch) {
                "arm64-v8a" -> "android-arm64"
                "armeabi-v7a" -> "android-arm"
                "x86_64" -> "android-x86_64"
                else -> arch
            }
            val assetName = "frida-gadget-$version-$fridaArch.so.xz"
            val url = "https://github.com/frida/frida/releases/download/$version/$assetName"
            logger.lifecycle("[frida] baixando $assetName de $url")
            val tmpXz = java.io.File(target.parentFile, "libfrida-gadget.so.xz")
            try {
                java.net.URI(url).toURL().openStream().use { input ->
                    tmpXz.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                }
                logger.lifecycle("[frida] descompactando ${tmpXz.length() / 1024} KB")
                decompressXz(tmpXz, target)
                target.setReadOnly()
                logger.lifecycle("[frida] $arch instalado (${target.length() / 1024} KB)")
            } catch (t: Throwable) {
                logger.warn("[frida] falhou para $arch: ${t.message}")
                target.delete()
                throw t
            } finally {
                tmpXz.delete()
            }
        }
    }

    private fun decompressXz(input: java.io.File, output: java.io.File) {
        val process = ProcessBuilder("xz", "-d", "-c", input.absolutePath)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        output.outputStream().use { out ->
            process.inputStream.use { it.copyTo(out, 64 * 1024) }
        }
        val code = process.waitFor()
        if (code != 0) throw IllegalStateException("xz falhou (código $code). Instale xz-utils.")
    }
}

tasks.register<DownloadFridaGadget>("downloadFridaGadget") {
    group = "larp"
    description = "Baixa libfrida-gadget.so do GitHub Releases da Frida para app/src/main/jniLibs/arm64-v8a/"
    fridaVersion.set(larpFridaVersion)
    jniDir.set(layout.projectDirectory.dir("src/main/jniLibs"))
}

android.applicationVariants.configureEach {
    preBuildProvider.get().dependsOn("downloadFridaGadget")
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.splashscreen)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.runtime)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    implementation(libs.coil.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.customtabs)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.common)

    implementation(libs.androidx.work.runtime)
    implementation(libs.accompanist.permissions)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
