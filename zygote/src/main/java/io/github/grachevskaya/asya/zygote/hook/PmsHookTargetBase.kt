package io.github.grachevskaya.asya.zygote.hook

import android.content.pm.IPackageManager
import android.os.Binder
import com.v7878.unsafe.invoke.EmulatedStackFrame
import io.github.grachevskaya.asya.common.Constants
import io.github.grachevskaya.asya.common.Utils.getUserFromCallingUid
import io.github.grachevskaya.asya.zygote.service.ReturnValue
import io.github.grachevskaya.asya.zygote.service.RewrittenArguments
import io.github.grachevskaya.asya.zygote.util.ServiceUtils.getPackageNameFromPackageSettings
import io.github.grachevskaya.asya.zygote.util.ZLUtils.getArgument
import io.github.grachevskaya.asya.zygote.util.ZLUtils.setArgument

/*
 * A hidden answer, for the package manager's own methods.
 *
 * No hook here replaces an answer: the argument the framework's method was handed is written over
 * and the method runs, so the framework itself resolves a name no package carries -- or is handed
 * nothing to generate a result from -- and produces the answer it produces for a package that is not
 * installed, after its own permission checks. The argument the call took away is put back when the
 * call is done, whatever the call answered, and nothing of the answer is touched.
 */
abstract class PmsHookTargetBase : IFrameworkHook {
    /**
     * The caller the framework was handed, not only the binder identity: an internal query made on
     * behalf of an app carries that app's uid in the frame. Zero and the system uid mean the
     * framework was told neither, and the binder's own identity is then the only answer left.
     */
    internal fun EmulatedStackFrame.callerUid(uidIndex: Int): Int {
        val fromFrame = getArgument(uidIndex) as? Int
        return if (fromFrame == null || fromFrame == 0 || fromFrame == Constants.UID_SYSTEM) {
            Binder.getCallingUid()
        } else {
            fromFrame
        }
    }

    /**
     * One rewritten call: the argument a hidden lookup must not carry is written over the asked one,
     * and the restore step of the same registration puts the asked one back when the call is done.
     *
     * The same work happens whatever the answer is -- the decision, the value a hidden call carries,
     * the write and the count, which is zero when nothing was hidden -- and only the value differs.
     * A caller can time a query, so the two answers must not be told apart by what they cost.
     */
    internal inline fun applyHiding(
        frame: EmulatedStackFrame,
        argumentIndex: Int,
        callingUid: Int,
        crossinline findTargetApp: (Any?) -> String?,
        crossinline hiddenArgument: (String) -> Any?,
        noinline findCallingApps: (IPackageManager, Int) -> Array<String>?,
    ) {
        val asked = frame.getArgument(argumentIndex)
        val targetApp = findTargetApp(asked)

        // Every path leaves the call paired: a slot is put in place whatever happens, and an empty
        // one is a call that took nothing away -- a query from the system, or one that named no
        // package at all.
        if (callingUid == Constants.UID_SYSTEM || targetApp == null) {
            RewrittenArguments.remember(null)
            return
        }

        val hidden = isHidden(callingUid, targetApp, findCallingApps)
        val hiddenValue = hiddenArgument(targetApp)
        RewrittenArguments.remember(if (hidden) asked else null)
        frame.setArgument(argumentIndex, if (hidden) hiddenValue else asked)
        service.increasePMFilterCount(callingUid, if (hidden) 1 else 0)
    }

    /**
     * The name rewrite: the asked name, or the form a query for a package that is not installed
     * carries -- and nothing else. Whether the asked name is hidden, and whether it is installed at
     * all, were answered when the policy was published, so a query is two set reads: no template walk,
     * no name resolution and no binder call stands between the ask and the answer.
     *
     * Every answer is the same statements: the table read, the two membership answers, the masked
     * name, and the three writes. A hidden name and a name that is not installed are written in the
     * same masked form -- a name of exactly the same length that no package carries -- so the
     * framework resolves both to nothing, and the two answers cannot be told apart by what the query
     * cost or by what the framework was handed. A name the caller may see is handed over as it is.
     */
    private val savedUidArray = ThreadLocal.withInitial<IntArray?> { null }

    /**
     * The bulk form of the same question: every uid this caller may not see is replaced by one nothing
     * ever had, so the reply has no name in those positions -- the same absence a uid that was never
     * handed out produces.
     */
    internal fun applyUidArrayRewrite(
        frame: EmulatedStackFrame,
        arrayIndex: Int,
        callingUid: Int,
    ) {
        savedUidArray.set(null)
        val uids = frame.getArgument(arrayIndex) as? IntArray ?: return
        if (dataHolder.hideTablesFor(callingUid) == null) return
        /*
         * The copy is taken whether or not this caller hides anything in the array: a run that hides a
         * uid and a run that hides none then pay the same allocation and the same two passes, and the
         * reply cannot be told apart by its cost.
         */
        val saved = uids.copyOf()
        var replaced = false
        for (i in uids.indices) {
            if (!dataHolder.isTargetUidHidden(callingUid, uids[i])) continue
            uids[i] = NO_SUCH_UID
            replaced = true
        }
        if (replaced) savedUidArray.set(saved)
    }

    internal fun restoreUidArray(
        frame: EmulatedStackFrame,
        arrayIndex: Int,
    ) {
        val saved = savedUidArray.get() ?: return
        savedUidArray.set(null)
        val uids = frame.getArgument(arrayIndex) as? IntArray ?: return
        System.arraycopy(saved, 0, uids, 0, minOf(saved.size, uids.size))
    }

    /**
     * The change history a caller gets back lists the packages whose state moved since boot; a name this
     * caller may not see is dropped from it, so the history carries no name the caller could not have
     * asked for. The sequence number is kept: only the entries change.
     */
    internal fun filterChangedPackages(
        result: ReturnValue,
        callingUid: Int,
    ) {
        val changes = result.result ?: return
        val type = changes.javaClass
        val field = type.getDeclaredField("packageNames").apply { isAccessible = true }
        val names = field.get(changes) as? List<*> ?: return
        val kept = names.filter { it is String && !dataHolder.isNameHidden(callingUid, it) }
        if (kept.size == names.size) return
        val sequence = type.getDeclaredField("sequenceNumber").apply { isAccessible = true }.getInt(changes)
        val ctor =
            type
                .getDeclaredConstructor(Int::class.javaPrimitiveType, List::class.java)
                .apply { isAccessible = true }
        result.result = ctor.newInstance(sequence, kept)
    }

    /**
     * The record the framework keeps of every package whose state moved since boot. It has no caller:
     * whatever is written is later readable by anyone, so a name this module hides is not written at all.
     */

    /**
     * Before Android 13 the record was filled from a package setting rather than from a name: the name
     * is the setting's own field, and the same rule applies -- a name this module hides is not written.
     */
    internal fun skipChangeRecordForSetting(
        returnValue: ReturnValue,
        setting: Any?,
    ) {
        val name =
            (setting?.javaClass)?.getDeclaredField("name")?.let { field ->
                field.isAccessible = true
                field.get(setting) as? String
            }
        skipChangeRecord(returnValue, name)
    }

    internal fun skipChangeRecord(
        returnValue: ReturnValue,
        packageName: String?,
    ) {
        if (packageName == null) return
        if (dataHolder.isHiddenFromAnybody(packageName)) returnValue.result = Unit
    }

    /**
     * The one place the caller of a hooked call is read. The argument the framework passes is used when it
     * is a uid it could have meant; otherwise the binder identity is, so every site answers for the same
     * caller and no site decides for itself which of the two to trust.
     */
    internal fun callerUidOf(
        frame: EmulatedStackFrame,
        uidIndex: Int,
    ): Int {
        val fromFrame = frame.callerUid(uidIndex)
        return if (fromFrame > 0) fromFrame else Binder.getCallingUid()
    }

    /**
     * A uid the framework has never handed out, answered in place of one this caller may not see: the
     * same "no such uid" a uid nothing ever had produces, so the answer carries no second meaning.
     */
    private val NO_SUCH_UID = 0x7fffffff

    private val savedUids = ThreadLocal.withInitial { IntArray(4) }

    /** Rewrites one uid argument; [restoreUid] puts the asked value back. */
    internal fun applyUidRewrite(
        frame: EmulatedStackFrame,
        uidIndex: Int,
        callingUid: Int,
    ) {
        val asked = frame.getArgument(uidIndex) as? Int
        if (asked == null || !dataHolder.isTargetUidHidden(callingUid, asked)) {
            savedUids.get()[0] = -1
            return
        }
        savedUids.get()[0] = asked
        frame.setArgument(uidIndex, NO_SUCH_UID)
    }

    internal fun restoreUid(
        frame: EmulatedStackFrame,
        uidIndex: Int,
    ) {
        val saved = savedUids.get()
        if (saved[0] == -1) return
        val asked = saved[0]
        saved[0] = -1
        frame.setArgument(uidIndex, asked)
    }

    internal inline fun applyNameRewrite(
        frame: EmulatedStackFrame,
        nameIndex: Int,
        callingUid: Int,
    ) {
        val asked = frame.getArgument(nameIndex) as? String

        // Every path leaves the call paired: a slot is put in place even when nothing is taken away.
        if (asked == null || callingUid == Constants.UID_SYSTEM) {
            RewrittenArguments.remember(null)
            return
        }

        // One entry, filled in place, and the name computed either way: the decision is the same
        // table reads whether the answer hides the name or hands it over.
        val entry = HideScratch.get()
        val tables = dataHolder.hideTablesFor(callingUid)
        val hidden = HideDecision.isHidden(tables, asked)
        entry.write = HideDecision.rewrittenName(tables, asked)
        entry.hidden = hidden

        RewrittenArguments.remember(if (hidden) asked else null)
        frame.setArgument(nameIndex, entry.write)
        service.increasePMFilterCount(callingUid, if (hidden) 1 else 0)
    }

    /**
     * The generator rewrite: the package setting a result would be generated from, or nothing at
     * all. The generator's own first line answers a null setting with a null result, which is what a
     * hidden lookup is meant to read.
     */
    internal inline fun applySettingNull(
        frame: EmulatedStackFrame,
        settingIndex: Int,
        callingUid: Int,
        noinline findCallingApps: (IPackageManager, Int) -> Array<String>?,
    ) = applyHiding(
        frame = frame,
        argumentIndex = settingIndex,
        callingUid = callingUid,
        findTargetApp = ::getPackageNameFromPackageSettings,
        hiddenArgument = { null },
        findCallingApps = findCallingApps,
    )

    /** Whether [targetApp] is hidden from [callingUid]: the table's own answer, or the caller behind. */
    internal fun isHidden(
        callingUid: Int,
        targetApp: String,
        findCallingApps: (IPackageManager, Int) -> Array<String>?,
    ): Boolean {
        // The table the lookups read comes first: a name it marks hidden is hidden here even when
        // the uid-pair table cannot place it, so an enumeration never answers a name differently
        // from the lookup that shares this decision.
        if (!HideDecision.keeps(dataHolder.hideTablesFor(callingUid), targetApp)) return true

        return dataHolder.shouldHideFromUid(callingUid, targetApp) == true ||
            run {
                val callingUserId = getUserFromCallingUid(callingUid)
                val callingApps = dataHolder.callingAppsOf(callingUid) { findCallingApps(pms, callingUid) }
                val caller = callingApps?.firstOrNull { service.shouldHide(it, targetApp, callingUserId) }

                caller?.let { dataHolder.putShouldHideUidCache(callingUid, it, targetApp) }
                caller != null
            }
    }

    /**
     * The other half of a rewritten call: the argument this call took away, written back into the
     * frame. A call that took nothing away has nothing to write back, which is why an early exit
     * still puts an empty slot in place.
     */
    internal fun putBackArgument(
        frame: EmulatedStackFrame,
        argumentIndex: Int,
    ) {
        RewrittenArguments.take()?.let { frame.setArgument(argumentIndex, it) }
    }

    /**
     * The one site whose answer is a decision rather than a value: the app filter is asked whether a
     * caller may see a package, and a hidden one is invisible exactly as a package that is not
     * installed is -- the caller sees the same thing either way, and the filter performs no permission
     * check, so nothing is skipped by answering here. The queries that never build a package (intent
     * resolution, package lists) reach this and nothing else.
     */
    internal fun applyFilterHiding(
        returnValue: ReturnValue,
        callingUid: Int,
        targetApp: String?,
        findCallingApps: (IPackageManager, Int) -> Array<String>?,
    ) {
        if (callingUid == Constants.UID_SYSTEM) return
        if (targetApp == null) return
        if (!isHidden(callingUid, targetApp, findCallingApps)) return

        returnValue.result = true
        service.increasePMFilterCount(callingUid)
    }
}

/**
 * The mask a rewritten name is mixed with, one character per character of the asked name and taken
 * from its start again when the name is longer than the mask.
 *
 * Every character of it has its high bit set, and so does every character of a masked name: a package
 * name is letters, digits, '_' and '.', so a masked name is a name no package carries and none can
 * carry -- which is what lets a hidden name and a name that is not installed be the same answer. The
 * characters still fit in one byte, so a masked name is exactly as long as the asked one in the
 * representation the framework's strings, comparisons and hashes use.
 */
private const val MASK = "\u0080\u0081\u0082\u0083"

/**
 * The name a query for a package that is not installed carries, and therefore the name a hidden query
 * carries as well: the asked name with every character mixed with a character of [MASK], one for one.
 *
 * Mixing is its own inverse, and that costs nothing: nothing is ever asked with a masked name, and a
 * masked name says nothing the framework acts on -- it is handed a name it does not have and answers
 * as it answers for a package that is not installed. What is written is the same length as what was
 * asked, so the write itself says nothing about which of the two answers it belongs to.
 */
internal fun xorName(asked: String): String {
    val name = StringBuilder(asked.length)
    for (index in asked.indices) {
        name.append((asked[index].code xor MASK[index % MASK.length].code).toChar())
    }

    return name.toString()
}

/**
 * The name a rewrite is answered in: the string written into the frame, and whether the query was
 * hidden.
 *
 * The fields are written in place because the query path holds one of these per thread and fills it
 * with the answer -- the hook's own scratch, reused, so that answering a query allocates nothing.
 * Nothing here is published: a thread's copy only ever holds the answer to the call being answered.
 */
internal class RewrittenName(
    var write: String,
    var hidden: Boolean,
)

/**
 * The scratch the name rewrite answers in: one entry per thread, reused.
 *
 * A rewritten call fills the entry and reads nothing else, so the two answers differ in what the entry
 * holds and in nothing a caller can time -- and because the entry is reused, answering a query
 * allocates nothing on either answer.
 */
internal object HideScratch {
    private val scratch = ThreadLocal.withInitial { RewrittenName("", false) }

    fun get(): RewrittenName = scratch.get()
}
