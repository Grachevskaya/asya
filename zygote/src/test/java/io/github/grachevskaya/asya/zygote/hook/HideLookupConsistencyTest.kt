package io.github.grachevskaya.asya.zygote.hook

import io.github.grachevskaya.asya.zygote.service.AsyaServiceDataHolder
import io.github.grachevskaya.asya.zygote.service.HideTables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * A package's two lookups, and the list that enumerates it, read one table and therefore give one
 * answer.
 *
 * The lookup is modelled as what the framework does with the name a site writes: a package exists
 * only under the name it was installed with, so a masked name resolves to nothing. The enumeration
 * is modelled with the 8192 flag's own semantics -- every known setting, installed or not -- and is
 * answered by the same table, so a hidden package cannot be in the list while its lookup is missing.
 */
class HideLookupConsistencyTest {
    private companion object {
        const val UID = 10101
        const val HIDDEN = "com.hidden.target"
        const val VISIBLE = "com.visible.target"
        const val UNINSTALLED = "com.uninstalled.target"
    }

    private val installed = setOf(HIDDEN, VISIBLE)
    private val tables = HideTables(hidden = setOf(HIDDEN), installed = installed)
    private val holder = AsyaServiceDataHolder { error("the test reads the published table") }

    init {
        holder.publishHideTables(mapOf(UID to tables))
    }

    private fun published() = holder.hideTablesFor(UID)

    /** What the framework answers when a site hands it [asked]. */
    private fun lookup(asked: String): String? = asked.takeIf { it in installed }

    /** What getPackageInfo would answer, through the name the query site writes. */
    private fun packageInfo(name: String) = lookup(HideDecision.rewrittenName(published(), name))

    /** What getApplicationInfo would answer, through the name the query site writes. */
    private fun applicationInfo(name: String) = lookup(HideDecision.rewrittenName(published(), name))

    /**
     * What an enumeration answers. With MATCH_UNINSTALLED_PACKAGES (8192) the framework walks every
     * known setting, so [UNINSTALLED] is in the input: the filter must keep it and drop only the
     * hidden package.
     */
    private fun enumerate(flags: Int = 8192): List<String> {
        val known = if (flags and 8192 != 0) listOf(HIDDEN, VISIBLE, UNINSTALLED) else installed.toList()
        return known.filter { HideDecision.keeps(published(), it) }
    }

    @Test fun aHiddenPackageIsMissingFromBothLookups() {
        assertTrue(HideDecision.isHidden(published(), HIDDEN))
        assertNull(packageInfo(HIDDEN))
        assertNull(applicationInfo(HIDDEN))
    }

    @Test fun aVisiblePackageIsAnsweredByBothLookups() {
        assertFalse(HideDecision.isHidden(published(), VISIBLE))
        assertEquals(VISIBLE, packageInfo(VISIBLE))
        assertEquals(VISIBLE, applicationInfo(VISIBLE))
    }

    @Test fun aNameNothingHasIsMissingFromBothLookups() {
        assertFalse(HideDecision.isHidden(published(), UNINSTALLED))
        assertNull(packageInfo(UNINSTALLED))
        assertNull(applicationInfo(UNINSTALLED))
    }

    @Test fun enumerationWith8192NeverCarriesAHiddenPackage() {
        val listed = enumerate()

        assertFalse(HIDDEN in listed)
        assertTrue(VISIBLE in listed)
        // 8192 exists to list what the user uninstalled; that is not hiding, so it stays.
        assertTrue(UNINSTALLED in listed)
        assertEquals(listOf(VISIBLE, UNINSTALLED), listed)
    }

    @Test fun theFilterReadsTheTableEvenWhenTheUidPairsAreAbsent() {
        // A configuration change empties the uid-pair table and keeps this one, and a name whose uid
        // could not be resolved is only in this one. The filter must still answer from it, or the
        // enumeration would carry a package the lookup hides.
        assertNotEquals(true, holder.shouldHideFromUid(UID, HIDDEN))
        assertTrue(HideDecision.isHidden(published(), HIDDEN))
        assertFalse(HIDDEN in enumerate())
    }

    @Test fun aNameAlreadyMaskedIsNotMaskedAgain() {
        // Masking is its own inverse: a second site in front of the first must leave the name alone,
        // because masking it again would hand the framework the real package back.
        val masked = HideDecision.rewrittenName(published(), HIDDEN)

        assertTrue(HideDecision.isMasked(masked))
        assertEquals(masked, HideDecision.rewrittenName(published(), masked))
        assertNull(lookup(HideDecision.rewrittenName(published(), masked)))
    }

    @Test fun aCallerWithNoTableKeepsEveryNameAndEveryRow() {
        assertFalse(HideDecision.isHidden(null, HIDDEN))
        assertEquals(HIDDEN, HideDecision.rewrittenName(null, HIDDEN))

        val all = listOf(HIDDEN, VISIBLE, UNINSTALLED)
        assertEquals(all, all.filter { HideDecision.keeps(null, it) })
    }
}
