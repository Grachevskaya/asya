package io.github.grachevskaya.asya.zygote.hook

import io.github.grachevskaya.asya.zygote.service.HideTables

/*
 * The one answer every hiding site reads.
 *
 * A caller's table says two things: the names it may not see, and the names the device has at all.
 * A lookup writes the asked name when the caller may see it and a masked name when it may not -- and
 * the same masked form when nothing has the name, so the two answers cannot be told apart. An
 * enumeration drops exactly the names the table marks hidden and keeps everything else, including
 * packages the user has uninstalled: those are what MATCH_UNINSTALLED_PACKAGES (8192) enumerates.
 *
 * Keeping the decision here is what makes a package's lookup and its place in a list one answer:
 * the sites read the same table through the same functions instead of each deciding for itself.
 */
internal object HideDecision {
    /** The bit every masked character carries; no package name carries a character with it set. */
    private const val MASK_BIT = 0x80

    /** Whether [name] is a name this module wrote rather than one a caller asked with. */
    fun isMasked(name: String): Boolean = name.any { it.code and MASK_BIT != 0 }

    /** Whether the caller behind [tables] may not see [name]. */
    fun isHidden(
        tables: HideTables?,
        name: String?,
    ): Boolean = name != null && tables?.hidden?.contains(name) == true

    /**
     * The name a lookup writes: the asked one when the caller may see it, the masked one when it is
     * hidden or when nothing has it.
     */
    fun nameFor(
        tables: HideTables?,
        asked: String,
    ): String {
        val installed = tables?.installed?.contains(asked) ?: true
        return if (isHidden(tables, asked) || !installed) xorName(asked) else asked
    }

    /**
     * The name a site writes when a site in front of it may already have written one: a masked name
     * is left alone, because masking it again would mix the mask off and hand the framework the real
     * package back.
     */
    fun rewrittenName(
        tables: HideTables?,
        asked: String,
    ): String = if (isMasked(asked)) asked else nameFor(tables, asked)

    /** Whether an enumerated [name] belongs in the caller's answer: only a hidden one is dropped. */
    fun keeps(
        tables: HideTables?,
        name: String?,
    ): Boolean = !isHidden(tables, name)
}
