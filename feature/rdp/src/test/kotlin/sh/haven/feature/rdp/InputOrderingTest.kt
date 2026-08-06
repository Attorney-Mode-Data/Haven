package sh.haven.feature.rdp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Collections

/**
 * #504: RDP input events must reach the session in the order the user made
 * them.
 *
 * They did not. Every key press, key release, button press and button release
 * was its own `launch(Dispatchers.IO)` — a multi-threaded pool with no
 * ordering guarantee between launches — so a release could overtake its own
 * press. A guest that receives release-then-press holds the key down, which is
 * the reporter's "last letter is repeating forever", and the same inversion on
 * a button is his "I have to press 20 times to minimize".
 *
 * The test is deliberately adversarial about it: the first event is slow and
 * the rest are not, so anything running them concurrently finishes them out of
 * order. Verified by mutation — switching the dispatcher back to
 * `Dispatchers.IO` fails it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InputOrderingTest {

    private val inputDispatcher = Dispatchers.IO.limitedParallelism(1)

    @Test
    fun `input events arrive in the order they were submitted`() {
        val arrivals = Collections.synchronizedList(mutableListOf<Int>())
        runBlocking {
            val scope = CoroutineScope(coroutineContext)
            // Submitted from one thread, as real input is: it all originates on
            // the main thread.
            repeat(EVENTS) { i ->
                scope.launch(inputDispatcher) {
                    // The first event is slow. On a pool, everything after it
                    // overtakes it; on an ordered dispatcher, nothing can.
                    if (i == 0) Thread.sleep(120)
                    arrivals.add(i)
                }
            }
        }
        assertEquals(
            "input was reordered — a key release can then precede its own press",
            (0 until EVENTS).toList(),
            arrivals.toList(),
        )
    }

    /**
     * The shape that actually bit: press then release, repeatedly. Out of
     * order, the guest is left holding the key.
     */
    @Test
    fun `a press is never overtaken by its own release`() {
        val arrivals = Collections.synchronizedList(mutableListOf<String>())
        runBlocking {
            val scope = CoroutineScope(coroutineContext)
            repeat(20) { i ->
                scope.launch(inputDispatcher) {
                    if (i % 2 == 0) Thread.sleep(5) // presses are the slow half
                    arrivals.add(if (i % 2 == 0) "down$i" else "up${i - 1}")
                }
            }
        }
        arrivals.chunked(2).forEach { pair ->
            assertEquals("each pair must be down-then-up, got $pair", 2, pair.size)
            assert(pair[0].startsWith("down")) { "release arrived before its press: $pair" }
            assert(pair[1].startsWith("up")) { "release arrived before its press: $pair" }
        }
    }

    private companion object {
        const val EVENTS = 40
    }
}
