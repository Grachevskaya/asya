package io.github.grachevskaya.asya.common

import io.github.grachevskaya.asya.common.Utils.cleanRemnantsFromConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The cleanup a config goes through before it is applied: an empty template is a remnant, and so is
 * a scope entry that names a template which is not there any more. What it must not do is take away
 * anything else -- the entry itself stays, and the templates that do exist stay named.
 */
class JsonConfigCleanupTest {
    private fun configWith(vararg templates: Pair<String, Set<String>>) =
        JsonConfig().apply {
            templates.forEach { (name, apps) ->
                this.templates[name] = JsonConfig.Template(isWhitelist = false, appList = apps)
            }
        }

    @Test
    fun anEmptyTemplateIsRemoved() {
        val config = configWith("empty" to emptySet(), "kept" to setOf("com.example.a"))

        config.cleanRemnantsFromConfig()

        assertEquals(setOf("kept"), config.templates.keys)
    }

    @Test
    fun anAppLosesTheTemplatesThatAreGone() {
        val config = configWith("kept" to setOf("com.example.a"))
        config.scope["com.example.target"] =
            JsonConfig.AppConfig(applyTemplates = mutableSetOf("kept", "gone"))

        config.cleanRemnantsFromConfig()

        assertEquals(setOf("kept"), config.scope.getValue("com.example.target").applyTemplates)
    }

    @Test
    fun theEntryItselfStaysEvenWhenNothingIsLeftToApply() {
        val config = JsonConfig()
        config.scope["com.example.target"] =
            JsonConfig.AppConfig(useWhitelist = true, applyTemplates = mutableSetOf("gone"))

        config.cleanRemnantsFromConfig()

        assertEquals(setOf("com.example.target"), config.scope.keys)
        assertTrue(
            config.scope
                .getValue("com.example.target")
                .applyTemplates
                .isEmpty(),
        )
        assertTrue(config.scope.getValue("com.example.target").useWhitelist)
    }

    @Test
    fun whatIsNotATemplateReferenceIsLeftAlone() {
        val config = configWith("kept" to setOf("com.example.a"))
        config.scope["com.example.target"] =
            JsonConfig.AppConfig(
                applyTemplates = mutableSetOf("kept"),
                extraAppList = mutableSetOf("com.example.hidden"),
                excludeVoldIsolation = true,
            )

        config.cleanRemnantsFromConfig()

        val entry = config.scope.getValue("com.example.target")
        assertEquals(setOf("kept"), entry.applyTemplates)
        assertEquals(setOf("com.example.hidden"), entry.extraAppList)
        assertTrue(entry.excludeVoldIsolation)
        assertEquals(setOf("com.example.a"), config.templates.getValue("kept").appList)
    }
}
