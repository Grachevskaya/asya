import com.android.ide.common.signing.KeystoreHelper
import com.v7878.zygisk.gradle.ZygoteLoader
import java.util.Locale
import kotlin.io.path.Path

plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.com.github.aerathstuff.zygoteloader)
}

val appPackageName = rootProject.extra["appPackageName"] as String
val appVerName = rootProject.extra["appVerName"] as String

android {
    namespace = "$appPackageName.zygote"

    defaultConfig {
        applicationId = namespace
        buildConfigField("String", "APP_PACKAGE_NAME", "\"$appPackageName\"")
    }

    buildFeatures {
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            java {
                directories.add(
                    Path(rootDir.path, "external", "AndroidVMTools", "src", "main", "java").toString(),
                )
            }
        }
    }
}

kotlin {
    jvmToolchain(21)
}

abstract class CopyManagerAppTask : DefaultTask() {
    @get:InputFile
    abstract val managerApk: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copyManagerApp() {
        managerApk.get().asFile.copyTo(
            outputDir.get().file("manager.apk").asFile,
            overwrite = true,
        )
    }
}

abstract class GenerateSignInfoTask : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    @get:Optional
    abstract val storeType: Property<String>

    @get:InputFile
    @get:Optional
    abstract val storeFile: RegularFileProperty

    @get:Input
    @get:Optional
    abstract val keyAlias: Property<String>

    @get:Internal
    abstract val storePassword: Property<String>

    @get:Internal
    abstract val keyPassword: Property<String>

    @TaskAction
    fun generate() {
        val certificateInfo =
            KeystoreHelper.getCertificateInfo(
                storeType.orNull,
                storeFile.asFile.orNull,
                storePassword.orNull,
                keyPassword.orNull,
                keyAlias.orNull,
            )

        val outSrc = outputDir.get().file("io/github/grachevskaya/asya/zygote/Magic.java").asFile
        outSrc.parentFile.mkdirs()

        val bytes = certificateInfo.certificate.encoded
        outSrc.writeText(
            buildString {
                appendLine("package io.github.grachevskaya.asya.zygote;")
                appendLine("public final class Magic {")
                appendLine("public static final byte[] magicNumbers = {")
                appendLine(bytes.joinToString(",") { it.toString() })
                appendLine("};")
                appendLine("}")
            },
        )
    }
}

androidComponents {
    onVariants { variant ->
        val variantCapped = variant.name.replaceFirstChar { it.titlecase(Locale.ROOT) }
        val variantLowered = variant.name.lowercase(Locale.ROOT)

        val managerApkFile =
            rootProject.layout.projectDirectory.file(
                "app/build/outputs/apk/$variantLowered/${rootProject.name}-$appVerName-$variantLowered.apk",
            )

        val copyManagerApp =
            tasks.register<CopyManagerAppTask>("copy${variantCapped}ManagerApp") {
                description = "Copies the manager APK into the $variantLowered module assets"

                dependsOn(":app:assemble$variantCapped")
                managerApk.set(managerApkFile)
            }
        variant.sources.assets?.addGeneratedSourceDirectory(
            copyManagerApp,
            CopyManagerAppTask::outputDir,
        )

        val sign = android.buildTypes[variantLowered].signingConfig
        val generateSignInfo =
            tasks.register<GenerateSignInfoTask>("generate${variantCapped}SignInfo") {
                description = "Generates the signature info used to verify the manager APK"

                storeType.set(sign?.storeType)
                storeFile.set(sign?.storeFile)
                storePassword.set(sign?.storePassword)
                keyAlias.set(sign?.keyAlias)
                keyPassword.set(sign?.keyPassword)
            }
        variant.sources.java?.addGeneratedSourceDirectory(
            generateSignInfo,
            GenerateSignInfoTask::outputDir,
        )
    }
}

zygisk {
    // inject to system_server
    // SystemUI is the host: it is always running, so the secret code always has someone to answer it.
    packages(ZygoteLoader.PACKAGE_SYSTEM_SERVER, "com.android.systemui")

    // module properties
    id = "asya"
    name = "Asya"
    author = "KodateMitsuru"
    description = "Z.A.T.O. // I Love the World and Everything In It"
    entrypoint = "io.github.grachevskaya.asya.zygote.ZygoteEntry"
    archiveName = "${rootProject.name}-${android.defaultConfig.versionName}"
}

dependencies {
    implementation(projects.common)
    compileOnly(projects.stub)

    implementation(libs.androidx.annotation.jvm)
    implementation(libs.io.github.vova7878.r8annotations)

    api(androidvmtools.panama.core)
    api(androidvmtools.panama.unsafe)
    api(androidvmtools.panama.llvm)

    implementation(androidvmtools.sun.cleaner)
}

/*
 * The annotations in com.v7878.r8.annotations are compile-time only, and two dependencies bring
 * them under two different coordinates: the loader's runtime brings the old group, the PanamaPort
 * artifacts bring the new one. One of the two has to go, and the old group is the one nobody is
 * publishing any more.
 */
/*
 * One copy of the annotations, and it is the older coordinate.
 *
 * Two dependencies bring the same classes under two groups -- the loader's runtime brings
 * com.github.vova7878, the PanamaPort artifacts bring io.github.vova7878 -- and because they carry
 * the same module name Gradle treats them as one module and keeps the higher version, which is the
 * one that does *not* have all of the annotations: DoNotShrink is in the older artifact and not in
 * the newer one. So the newer coordinate is excluded everywhere, the older one is declared for
 * compilation, and the runtime gets it from the loader.
 */
configurations.all {
    exclude(group = "io.github.vova7878", module = "R8Annotations")
}

dependencies {
    compileOnly("com.github.vova7878:R8Annotations:v0.0.2")
}
