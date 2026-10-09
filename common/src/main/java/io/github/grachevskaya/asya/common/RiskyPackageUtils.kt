package io.github.grachevskaya.asya.common

class RiskyPackageUtils private constructor() {
    companion object {
        val instance by lazy { RiskyPackageUtils() }
    }

    // Apps that connect to play services without declaring it in their manifest. The list is the
    // whole check: an entry here is what marks such an app.
    private val explicitlyIgnoredPackages =
        arrayOf(
            "com.anydesk.anydeskandroid",
        )

    fun appHasGMSConnection(query: String) = query in explicitlyIgnoredPackages
}
