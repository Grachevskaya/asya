package io.github.grachevskaya.asya.common

import android.content.pm.ApplicationInfo
import io.github.grachevskaya.asya.common.Utils.checkSplitPackages

class RiskyPackageUtils private constructor() {
    companion object {
        val instance by lazy { RiskyPackageUtils() }

        private const val GMS_PROP = "\u0000c\u0000o\u0000m\u0000.\u0000g\u0000o\u0000o\u0000g\u0000l\u0000e\u0000.\u0000a\u0000n\u0000d\u0000r\u0000o\u0000i\u0000d\u0000.\u0000g\u0000m\u0000s\u0000."
        private const val FIREBASE_PROP = "\u0000c\u0000o\u0000m\u0000.\u0000g\u0000o\u0000o\u0000g\u0000l\u0000e\u0000.\u0000f\u0000i\u0000r\u0000e\u0000b\u0000a\u0000s\u0000e\u0000."
    }

    private val ignoredForRiskyPackagesList = mutableSetOf<String>()

    // Add apps in that list when they have connections but not specified in the manifest file
    private val explicitlyIgnoredPackages = arrayOf(
        "com.anydesk.anydeskandroid",
    )

    // The manifest of an apk is binary, and the markers above are stored as UTF-16, which is
    // why they are written with a NUL between every character: reading the entry as bytes and
    // decoding them one byte per character is what makes them match. It was the presets' job
    // to read manifests before; nothing else needs them now.
    private fun readManifest(zipFile: ZipFile): String {
        val entry = zipFile.getEntry("AndroidManifest.xml") ?: return ""
        return zipFile.getInputStream(entry).use { stream ->
            String(stream.readBytes(), Charsets.ISO_8859_1)
        }
    }

    fun appHasGMSConnection(query: String, excludeExplicit: Boolean = false) =
        query in ignoredForRiskyPackagesList || (!excludeExplicit && query in explicitlyIgnoredPackages)

    internal fun tryToAddIntoGMSConnectionList(appInfo: ApplicationInfo, loggerFunction: ((String) -> Unit)?): Boolean {
        val packageName = appInfo.packageName

        if (appHasGMSConnection(packageName)) return false

        return checkSplitPackages(appInfo) { key, zipFile ->
            val manifestStr = readManifest(zipFile)

            // Checking with binary because the Android system sucks
            if (manifestStr.contains(GMS_PROP) || manifestStr.contains(FIREBASE_PROP)) {
                if (ignoredForRiskyPackagesList.add(packageName)) {
                    loggerFunction?.invoke("@appHasGMSConnection $packageName added in ignored packages list")
                    return@checkSplitPackages true
                }
            }

            return@checkSplitPackages false
        }
    }

    internal fun importCache(elements: MutableSet<String>) = ignoredForRiskyPackagesList.addAll(elements)

    internal fun exportCache(): Set<String> = ignoredForRiskyPackagesList

    internal fun removeAppFromList(packageName: String) = ignoredForRiskyPackagesList.remove(packageName)

    internal fun removeAppsFromListIfNotExists(packageNames: Iterable<String>) =
        ignoredForRiskyPackagesList.removeIf { it !in packageNames }

    internal fun clearAppList() = ignoredForRiskyPackagesList.clear()
}
