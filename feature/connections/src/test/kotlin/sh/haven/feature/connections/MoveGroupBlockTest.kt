package sh.haven.feature.connections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Index arithmetic behind group reordering (#490).
 *
 * The screen's flat list encodes group membership by position — a connection
 * belongs to the last `group-` header above it — so these tests care about one
 * thing above all: no connection may change group as a side effect of moving
 * one.
 */
class MoveGroupBlockTest {

    /** `[u1, A: a1 a2, B: b1, C: c1 c2]` — ungrouped first, three groups. */
    private val ids = listOf(
        "u1",
        "group-A", "a1", "a2",
        "group-B", "b1",
        "group-C", "c1", "c2",
    )

    /** Membership as the screen derives it, so tests can assert it is intact. */
    private fun membership(list: List<String>): Map<String, String?> {
        var current: String? = null
        return buildMap {
            list.forEach { key ->
                if (key.startsWith("group-")) current = key.removePrefix("group-") else put(key, current)
            }
        }
    }

    @Test
    fun `moving a group up carries its connections`() {
        assertEquals(
            listOf("u1", "group-B", "b1", "group-A", "a1", "a2", "group-C", "c1", "c2"),
            moveGroupBlock(ids, "B", up = true),
        )
    }

    @Test
    fun `moving a group down carries its connections`() {
        assertEquals(
            listOf("u1", "group-A", "a1", "a2", "group-C", "c1", "c2", "group-B", "b1"),
            moveGroupBlock(ids, "B", up = false),
        )
    }

    @Test
    fun `no connection changes group, whichever way a group moves`() {
        val before = membership(ids)
        listOf("A" to false, "B" to true, "B" to false, "C" to true).forEach { (gid, up) ->
            val moved = moveGroupBlock(ids, gid, up)!!
            assertEquals("moving $gid ${if (up) "up" else "down"} moved a connection", before, membership(moved))
        }
    }

    @Test
    fun `ungrouped connections stay above the first header`() {
        // The hazard case: the first group moving up would otherwise land on
        // top of u1 and swallow it.
        assertNull(moveGroupBlock(ids, "A", up = true))
        val down = moveGroupBlock(ids, "A", up = false)!!
        assertEquals("u1", down.first())
        assertNull(membership(down)["u1"])
    }

    @Test
    fun `the last group cannot move down`() {
        assertNull(moveGroupBlock(ids, "C", up = false))
    }

    @Test
    fun `an unknown group is not a move`() {
        assertNull(moveGroupBlock(ids, "nope", up = true))
    }

    @Test
    fun `a move never adds or drops an entry`() {
        val moved = moveGroupBlock(ids, "C", up = true)!!
        assertEquals(ids.sorted(), moved.sorted())
    }

    @Test
    fun `an empty group moves like any other`() {
        val withEmpty = listOf("group-A", "a1", "group-B", "group-C", "c1")
        assertEquals(
            listOf("group-A", "a1", "group-C", "c1", "group-B"),
            moveGroupBlock(withEmpty, "B", up = false),
        )
    }

    @Test
    fun `a group at the top of a list with no ungrouped connections still moves down`() {
        val noUngrouped = listOf("group-A", "a1", "group-B", "b1")
        assertEquals(
            listOf("group-B", "b1", "group-A", "a1"),
            moveGroupBlock(noUngrouped, "A", up = false),
        )
    }
}
