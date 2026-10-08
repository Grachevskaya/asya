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
    fun anUnknownKeyInsideANestedObjectIsIgnoredLikeATopLevelOne() {
        // The tolerance is the encoder's, so it covers every level: a field a newer manager writes
        // into a scope entry, a template or a hook item must not make the whole config unreadable.
        val read =
            JsonConfig.parse(
                """{"configVersion":5,"scope":{"com.example.a":{"useWhitelist":true,"aFieldFromTheFuture":7}},""" +
                    """ "templates":{"t":{"isWhitelist":false,"appList":["com.example.b"],"alsoNew":1}},""" +
                    """ "disabledHooks":[{"className":"c","methodName":"m","argumentCount":2,"extra":true}]}""",
            )

        assertTrue(read.scope.getValue("com.example.a").useWhitelist)
        assertEquals(setOf("com.example.b"), read.templates.getValue("t").appList)
        assertEquals(2, read.disabledHooks.first().argumentCount)
    }

    @Test
    fun aKnownKeyWithTheWrongTypeIsStillAnError() {
        // Tolerance is for keys the schema does not know, not for values that do not fit it.
        var threw = false

        try {
            JsonConfig.parse("""{"configVersion":"five"}""")
        } catch (cause: Throwable) {
            threw = true
        }

        assertTrue(threw)
    }

    @Test
    fun aConfigThatCarriesNothingButTheVersionParses() {
        // An old config, or a hand written one: every field but the version is absent and the
        // defaults have to be what the service then runs with.
        val read = JsonConfig.parse("""{"configVersion":3}""")

        assertEquals(3, read.configVersion)
        assertEquals(512, read.maxLogSize)
        assertTrue(read.forceMountData)
        assertTrue(read.templates.isEmpty())
        assertTrue(read.scope.isEmpty())
        assertTrue(read.disabledHooks.isEmpty())
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
    fun everyFieldOfAConfigSurvivesTheRoundTrip() {
        val config =
            JsonConfig().apply {
                configVersion = 31
                detailLog = true
                errorOnlyLog = true
                maxLogSize = 2048
                forceMountData = false
                disableActivityLaunchProtection = true
                altAppDataIsolation = true
                altVoldAppDataIsolation = true
                skipSystemAppDataIsolation = false
                webViewProtection = false
                ignoreBrandProtections = true
                disabledHooks.add(JsonConfig.HookItem("com.example.holder", "method", 1))
                defaultConfig = JsonConfig.AppConfig(useWhitelist = true)
                scope["com.example.a"] =
                    JsonConfig.AppConfig(
                        useWhitelist = true,
                        excludeSystemApps = false,
                        hideInstallationSource = true,
                        hideSystemInstallationSource = true,
                        excludeTargetInstallationSource = true,
                        invertActivityLaunchProtection = true,
                        excludeVoldIsolation = true,
                        applyTemplates = mutableSetOf("held"),
                        extraAppList = mutableSetOf("com.example.hidden"),
                        extraOppositeAppList = mutableSetOf("com.example.shown"),
                    )
                templates["held"] = JsonConfig.Template(isWhitelist = false, appList = setOf("com.example.a"))
            }

        assertEquals(config, JsonConfig.parse(config.toString()))
    }

    @Test
    fun theInnerObjectsSurviveTheirOwnRoundTrip() {
        val app = JsonConfig.AppConfig(useWhitelist = true, extraOppositeAppList = mutableSetOf("com.example.x"))
        assertEquals(app, JsonConfig.AppConfig.parse(app.toString()))

        val template = JsonConfig.Template(isWhitelist = true, appList = setOf("com.example.a"))
        val carried = JsonConfig.parse(JsonConfig().apply { templates["held"] = template }.toString())
        assertEquals(template, carried.templates.getValue("held"))

        val hook = JsonConfig.HookItem("android.content.pm.PackageManager", "getInstalledPackages", 2)
        assertEquals(hook, JsonConfig.HookItem.parse(hook.toString()))
    }
}
