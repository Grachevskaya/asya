package io.github.grachevskaya.asya.zygote.hook

import io.github.grachevskaya.asya.zygote.service.AsyaServiceDataHolder
import io.github.grachevskaya.asya.zygote.service.HideTables
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/*
 * The entry the name rewrite answers in.
 *
 * It is one per thread and reused, so answering a query allocates nothing whatever the answer is, and
 * the entries a table publishes are never written: the copy a call keeps is the thread's own.
 */
class HideScratchTest {
    @Test fun aThreadAnswersInOneEntryItReuses() {
        assertSame(HideScratch.get(), HideScratch.get())
    }

    @Test fun anotherThreadAnswersInAnEntryOfItsOwn() {
        val here = HideScratch.get()
        val there = AtomicReference<RewrittenName>()
        val answered = CountDownLatch(1)

        Thread {
            there.set(HideScratch.get())
            answered.countDown()
        }.apply { isDaemon = true }.start()
        answered.await()

        assertNotSame(here, there.get())
    }

    @Test fun fillingTheScratchLeavesThePublishedTableAlone() {
        val holder = AsyaServiceDataHolder { error("a query reads the published table, not a resolver") }
        val published =
            HideTables(hidden = setOf("com.target"), installed = setOf("com.target", "com.visible"))
        holder.publishHideTables(mapOf(10101 to published))

        val entry = HideScratch.get()
        entry.write = "com.something.else"
        entry.hidden = true

        assertSame(published, holder.hideTablesFor(10101))
        assertTrue(holder.hideTablesFor(10101)?.hidden?.contains("com.target") == true)
        assertTrue(holder.hideTablesFor(10101)?.installed?.contains("com.visible") == true)
    }
}
