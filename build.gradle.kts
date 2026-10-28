import com.android.build.api.dsl.ApkSigningConfig
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.LibraryExtension
import com.android.repository.Revision
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.agp.app) apply false
    alias(libs.plugins.agp.lib) apply false
    alias(libs.plugins.nav.safeargs.kotlin) apply false
    alias(libs.plugins.com.github.aerathstuff.zygoteloader) apply false
    alias(libs.plugins.ktlint) apply false
}

fun String.execute(currentWorkingDir: File = file("./")): String {
    val out = providers.exec {
        workingDir = currentWorkingDir
        commandLine = split("\\s".toRegex())
    }
    return out.standardOutput.asText.get().trim()
}

val localProperties = Properties()
localProperties.load(file("local.properties").inputStream())
val ciBuild = providers.environmentVariable("CI").isPresent
val officialBuild = localProperties.getProperty("officialBuild", "false") == "true"
extra["officialBuild"] = officialBuild

fun getUncommittedSuffix(): String {
    if (officialBuild) return ""

    val shortRef = "git rev-parse --short HEAD".execute()

    if (ciBuild) {
        val headRefVal = providers.environmentVariable("GITHUB_HEAD_REF").orElse("HEAD").get()
        return "$headRefVal-$shortRef"
    }

    var returnedVal = ""

    try {
        val branch = "git rev-parse --abbrev-ref HEAD".execute().split("/").last()
        if (branch != "master") {
            returnedVal += "$branch-"
        }
    } catch (_: Throwable) {}

    returnedVal += shortRef

    val result = "git status -s".execute()
    if (result.isEmpty()) {
        return returnedVal
    }

    return "$returnedVal+${result.count { it == '\n' } + 1}"
}

val gitVersionName: String get() {
    val suffix = getUncommittedSuffix()

    return suffix.ifEmpty {
        "$gitCommitCountAfterLicense"
    }
}

// This repository's default branch is main, and a full checkout fetches every branch of it.
val gitCommitCount = "git rev-list refs/remotes/origin/main --count".execute().toInt()

// 432 is the count of commits before the license changed
val gitCommitCountAfterLicense = gitCommitCount - 432

// Android 12 is the oldest release this module installs on: the hook sets below it are gone.
val minSdkVer = 31
extra["minSdkVer"] = minSdkVer
val targetSdkVer = 37
extra["targetSdkVer"] = targetSdkVer

val minorSdkVer = 0
extra["minorSdkVer"] = minorSdkVer

val appVerCode = gitCommitCount + 0x6f7373 // the commit count over this lineage's fixed base
extra["appVerCode"] = appVerCode
val appVerName = gitVersionName
extra["appVerName"] = appVerName

/*
 * configVerCode, serviceVerCode and minBackupVerCode is used by other build.gradle.kts files
 *
 * DO NOT REMOVE THESE LINES
*/

val configVerCode = 93
extra["configVerCode"] = configVerCode

val serviceVerCode = 105
extra["serviceVerCode"] = serviceVerCode

val minBackupVerCode = 65
extra["minBackupVerCode"] = minBackupVerCode

val appPackageName = "io.github.grachevskaya.asya"
extra["appPackageName"] = appPackageName

val localBuild = localProperties.getProperty("localBuild", "false") == "true"
extra["localBuild"] = localBuild

val androidSourceCompatibility = JavaVersion.VERSION_21
val androidTargetCompatibility = JavaVersion.VERSION_21

tasks.register("clean", Delete::class) {
    description = "Clean the build directory"
    delete(rootProject.layout.buildDirectory)
}

fun Project.createProvidedSigningConfig(android: CommonExtension): ApkSigningConfig? {
    val keystorePath = localProperties.getProperty("fileDir") ?: return null

    logger.lifecycle("Using provided signing key")

    return android.signingConfigs.create("config").apply {
        storeFile = file(keystorePath)
        storePassword = localProperties.getProperty("storePassword")
        keyAlias = localProperties.getProperty("keyAlias")
        keyPassword = localProperties.getProperty("keyPassword")
    }
}

private fun CommonExtension.applySharedAndroidConfig() {
    buildToolsVersion = providers.gradleProperty("android.buildToolsVersion").orNull
        ?: file(localProperties.getProperty("sdk.dir")
            ?: providers.environmentVariable("ANDROID_HOME")
                .orElse(providers.environmentVariable("ANDROID_SDK_ROOT")).get())
            .resolve("build-tools").listFiles().orEmpty()
            .filter { it.isDirectory && it.resolve("aapt2").isFile }
            .maxByOrNull { Revision.parseRevision(it.name) }?.name
        ?: throw GradleException("No Android Build Tools installation found")

    compileSdk {
        version = release(targetSdkVer) {
            minorApiLevel = minorSdkVer
        }
    }

    compileOptions.sourceCompatibility = androidSourceCompatibility
    compileOptions.targetCompatibility = androidTargetCompatibility
}

fun Project.configureBaseExtension() {
    extensions.findByType<ApplicationExtension>()?.run {
        applySharedAndroidConfig()

        defaultConfig {
            minSdk = minSdkVer
            targetSdk = targetSdkVer
            versionCode = appVerCode
            versionName = appVerName
        }

        val providedSigningConfig = createProvidedSigningConfig(this)

        buildTypes {
            all {
                signingConfig = providedSigningConfig ?: signingConfigs.getByName("debug")
            }
            named("release") {
                isMinifyEnabled = true
                isShrinkResources = true
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro",
                )
            }
        }

        dependenciesInfo {
            // Disables dependency metadata when building APKs (for IzzyOnDroid/F-Droid)
            includeInApk = false
            // Disables dependency metadata when building Android App Bundles (for Google Play)
            includeInBundle = false
        }
    }

    extensions.findByType<LibraryExtension>()?.run {
        applySharedAndroidConfig()

        defaultConfig {
            minSdk = minSdkVer
            consumerProguardFiles("proguard-rules.pro")
        }

        val providedSigningConfig = createProvidedSigningConfig(this)

        buildTypes {
            all {
                signingConfig = providedSigningConfig ?: signingConfigs.getByName("debug")
            }
            named("release") {
                isMinifyEnabled = true
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro",
                )
            }
        }
    }
}

subprojects {
    plugins.withId("com.android.application") {
        configureBaseExtension()
    }
    plugins.withId("com.android.library") {
        configureBaseExtension()
    }
}

/*
 * ktlint reads .editorconfig, which is where this tree states the style it is written in and the
 * two rules it turns off. Every module is checked, and a finding fails the build: the tree is
 * clean, and what is checked is what stays clean.
 */
subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
}

