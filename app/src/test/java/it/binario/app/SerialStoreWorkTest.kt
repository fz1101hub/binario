package it.binario.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The punctuality history is written off the main thread without losing a change (second audit, android-state-10). */
class SerialStoreWorkTest {
    @Test fun `changes run off the caller in order, each from the state the one before published`() = runTest {
        var state = emptyList<String>()
        val published = mutableListOf<List<String>>()
        val workerThreads = mutableListOf<Thread>()
        val caller = Thread.currentThread()
        val work = SerialStoreWork(this, Dispatchers.Default, read = { state }) { state = it; published += it }
        // The first change (the read at start) is the slowest: the record asked meanwhile waits for it.
        val load = work.submit { Thread.sleep(50); workerThreads += Thread.currentThread(); it + "stored" }
        val record = work.submit { workerThreads += Thread.currentThread(); it + "observed" }
        load.join()
        record.join()
        assertEquals(listOf(listOf("stored"), listOf("stored", "observed")), published)
        assertEquals(listOf("stored", "observed"), state)
        workerThreads.forEach { assertNotEquals(caller, it) }
    }
}
