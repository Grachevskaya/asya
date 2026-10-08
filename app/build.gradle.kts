plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.nav.safeargs.kotlin)
}

val appPackageName = rootProject.extra["appPackageName"] as String
val localBuild = rootProject.extra["localBuild"] as Boolean
val officialBuild = rootProject.extra["officialBuild"] as Boolean

android {
    namespace = appPackageName

    defaultConfig {
        buildConfigField("String[]", "SUPPORTED_LOCALES", generateSupportedLocales())

        // The bridge payload tests are instrumented: they need a Parcel and shared memory.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    packaging {
        dex.useLegacyPackaging = true
        resources {
            excludes +=
                arrayOf(
                    "/META-INF/*",
                    "/META-INF/androidx/**",
                    "/kotlin/**",
                    "/okhttp3/**",
                )
        }
    }
}

base {
    archivesName = "${rootProject.name}-${android.defaultConfig.versionName!!.replace("/", "_")}"
}

kotlin {
    jvmToolchain(21)
}

// Inspired from https://github.com/XayahSuSuSu/Android-DataBackup/pull/260
fun generateSupportedLocales(): String {
    val foundLocales = StringBuilder()
    foundLocales.append("new String[]{")

    fun appendLangCode(code: String) {
        foundLocales
            .append("\"")
            .append(code)
            .append("\"")
            .append(",")
    }

    appendLangCode("SYSTEM")

    fileTree(
        android.sourceSets["main"]
            .res.directories
            .first(),
    ).files
        .mapNotNull {
            if (it.name == "strings.xml") {
                val baseName = it.parent.substringAfterLast(File.separator)
                if (baseName == "values") {
                    "en"
                } else {
                    baseName
                        .substringAfter('-')
                        .replace("-r", "-")
                }
            } else {
                null
            }
        }.sortedWith { file1, file2 ->
            file1.compareTo(file2)
        }.forEach { appendLangCode(it) }

    return "${foundLocales.removeSuffix(",")}}"
}

dependencies {
    implementation(projects.common)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)

    // The bridge is written against hidden framework types: the test needs their shapes to compile.
    androidTestCompileOnly(projects.stub)

    implementation(libs.androidx.navigation.fragment.ktx)

    // The fragment and activity the libraries above are written against: what resolved before was
    // fragment 1.6.2, which predates predictive back entirely, so no amount of navigation support
    // could have shown a back gesture. 1.9 is where the fragment side of it lives.
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.io.coilkt.coil3.coil)
    implementation(libs.io.coilkt.coil3.coil.network.okhttp)
    implementation(libs.dev.androidbroadcast.vbpd)
    implementation(libs.dev.androidbroadcast.vbpd.reflection)

    implementation(libs.androidx.appcompat.appcompat)
    implementation(libs.material)
}
