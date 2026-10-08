package io.github.grachevskaya.asya.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The config is the one thing the manager and the service both write and read, and a field that
 * stops surviving a round trip changes the hiding without anything failing. The tests below hold the
 * two ends of that: what a fresh config carries, and what a written one gives back.
 */
class JsonConfigTest {
    @Test
    fun aFreshConfigCarriesTheBuildsVersion() {
        assertEquals(BuildConfig.CONFIG_VERSION, JsonConfig().configVersion)
    }

    @Test
    fun aFreshConfigCarriesTheDocumentedDefaults() {
        val config = JsonConfig()

        assertFalse(config.detailLog)
        assertFalse(config.errorOnlyLog)
        assertTrue(config.forceMountData)
        assertTrue(config.skipSystemAppDataIsolation)
        assertTrue(config.webViewProtection)
        assertEquals(512, config.maxLogSize)
    }

    @Test
    fun defaultsAreWrittenOut() {
        // encodeDefaults is what lets an older service read a newer config without guessing, and
        // what makes a written file self describing.
        val json = JsonConfig().toString()

        assertTrue(json.contains("\"forceMountData\":true"))
        assertTrue(json.contains("\"maxLogSize\":512"))
    }

    @Test
    fun aWrittenConfigComesBackWhole() {
        val config =
            JsonConfig().apply {
                configVersion = 17
                detailLog = true
                defaultConfig =
                    JsonConfig.AppConfig(
                        useWhitelist = true,
                        applyTemplates = mutableSetOf("lsposed", "root"),
                        extraAppList = mutableSetOf("com.example.hidden"),
                    )
                templates["lsposed"] =
                    JsonConfig.Template(isWhitelist = false, appList = setOf("com.example.a", "com.example.b"))
                scope["com.example.target"] =
                    JsonConfig.AppConfig(useWhitelist = false, excludeSystemApps = false, excludeVoldIsolation = true)
            }

        val read = JsonConfig.parse(config.toString())

        assertEquals(17, read.configVersion)
        assertTrue(read.detailLog)
        assertEquals(setOf("com.example.a", "com.example.b"), read.templates.getValue("lsposed").appList)
        assertFalse(read.templates.getValue("lsposed").isWhitelist)
        assertEquals(setOf("com.example.target"), read.scope.keys)
        assertTrue(read.scope.getValue("com.example.target").excludeVoldIsolation)
        assertFalse(read.scope.getValue("com.example.target").excludeSystemApps)
        assertNotNull(read.defaultConfig)
        assertTrue(read.defaultConfig!!.useWhitelist)
        assertEquals(setOf("lsposed", "root"), read.defaultConfig!!.applyTemplates)
        assertEquals(setOf("com.example.hidden"), read.defaultConfig!!.extraAppList)
    }

    @Test
    fun aConfigFromANewerManagerStillParses() {
        // ignoreUnknownKeys: a manager that knows a field the service does not must not make the
        // whole config unreadable.
        val read = JsonConfig.parse("""{"configVersion":21,"detailLog":true,"aFieldFromTheFuture":42}""")

        assertEquals(21, read.configVersion)
        assertTrue(read.detailLog)
    }

    @Test
    fun theInnerObjectsSurviveTheirOwnRoundTrip() {
        val app = JsonConfig.AppConfig(useWhitelist = true, extraOppositeAppList = mutableSetOf("com.example.x"))
        assertEquals(app, JsonConfig.AppConfig.parse(app.toString()))

        val template = JsonConfig.Template(isWhitelist = true, appList = setOf("com.example.a"))
        assertTrue(template.toString().contains("\"isWhitelist\":true"))
        assertTrue(template.toString().contains("com.example.a"))

        val hook = JsonConfig.HookItem("android.content.pm.PackageManager", "getInstalledPackages", 2)
        assertEquals(hook, JsonConfig.HookItem.parse(hook.toString()))
    }
}
