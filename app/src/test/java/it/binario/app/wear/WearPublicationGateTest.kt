package it.binario.app.wear

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WearPublicationGateTest {
    @Test
    fun `revocation is last and invalidates publications already queued`() = runBlocking {
        val gate = WearPublicationGate()
        val token = gate.publicationToken()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            gate.runPublication(token) {
                events += "put-start"
                firstEntered.complete(Unit)
                releaseFirst.await()
                events += "put-end"
                "published"
            }
        }
        firstEntered.await()
        val stale = async(start = CoroutineStart.UNDISPATCHED) {
            gate.runPublication(token) {
                events += "stale-put"
                "stale"
            }
        }

        gate.revokePublications()
        val delete = async(start = CoroutineStart.UNDISPATCHED) {
            gate.runExclusive {
                events += "delete"
                "deleted"
            }
        }
        releaseFirst.complete(Unit)

        assertEquals("published", first.await())
        assertNull(stale.await())
        assertEquals("deleted", delete.await())
        assertEquals(listOf("put-start", "put-end", "delete"), events)
    }

    @Test
    fun `newer revocation invalidates an enable intent waiting for the gate`() = runBlocking {
        val gate = WearPublicationGate()
        val publicationToken = gate.publicationToken()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val publication = async(start = CoroutineStart.UNDISPATCHED) {
            gate.runPublication(publicationToken) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()

        val enableIntent = gate.revokePublications()
        val enable = async(start = CoroutineStart.UNDISPATCHED) {
            gate.runExclusive { gate.isCurrent(enableIntent) }
        }
        gate.revokePublications()
        release.complete(Unit)

        publication.await()
        assertEquals(false, enable.await())
    }
}
