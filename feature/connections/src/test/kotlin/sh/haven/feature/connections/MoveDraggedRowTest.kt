package sh.haven.feature.connections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dragging a connection past a collapsed group (#488).
 *
 * The list on screen is not the list being reordered: members of a collapsed
 * group are absent from it. The reporter's list had two collapsed groups of
 * three above three expanded ones, and connections at the bottom could not be
 * dragged into the collapsed groups or above them at all — the neighbour the
 * drag wanted to step over was a row that had never been rendered.
 *
 * So these tests are mostly about that gap between the two lists, and about the
 * one invariant that survives it: no *other* connection may change group as a
 * side effect of moving one.
 */
class MoveDraggedRowTest {

    /**
     * The reporter's list: Local and Remote collapsed with three each, Cloud
     * and Windows empty, Linux expanded with the three connections he was
     * dragging.
     */
    private val ids = listOf(
        "group-Local", "l1", "l2", "l3",
        "group-Remote", "r1", "r2", "r3",
        "group-Cloud",
        "group-Windows",
        "group-Linux", "cacn", "cloud", "win",
    )

    private val collapsed = setOf("Local", "Remote")

    /**
     * What the screen renders. The production filter, not a copy of it — the
     * whole bug was the two lists disagreeing, so a test with its own idea of
     * which rows exist would be testing the wrong thing.
     */
    private fun displayedOf(list: List<String>, dragged: String? = null): List<String> =
        displayedRows(list, collapsed, dragged)

    /** Membership as the screen derives it: the last header above a row. */
    private fun membership(list: List<String>): Map<String, String?> {
        var current: String? = null
        return buildMap {
            list.forEach { key ->
                if (key.startsWith("group-")) current = key.removePrefix("group-") else put(key, current)
            }
        }
    }

    /** One drag step, using the same displayed list the screen would build. */
    private fun drag(list: List<String>, id: String, down: Boolean): List<String>? =
        moveDraggedRow(list, displayedOf(list, dragged = id), id, down)

    @Test
    fun `a connection can be dragged up out of the group below a collapsed one`() {
        // cacn is the first row under group-Linux. One step up takes it out of
        // Linux and into Windows, the group above.
        val moved = drag(ids, "cacn", down = false)!!
        assertEquals("Windows", membership(moved)["cacn"])
    }

    @Test
    fun `a connection walks all the way to ungrouped, crossing both collapsed groups`() {
        // This is the reported bug: the walk used to stop at the row after r3,
        // because r3 is a member of a collapsed group and was never rendered.
        var list = ids
        val visited = mutableListOf<String?>()
        repeat(5) {
            list = drag(list, "cacn", down = false) ?: error("stuck at ${membership(list)["cacn"]}")
            visited += membership(list)["cacn"]
        }
        assertEquals(listOf("Windows", "Cloud", "Remote", "Local", null), visited)
        assertEquals("cacn", list.first())
    }

    @Test
    fun `dragging down onto a collapsed header joins that group, ahead of its members`() {
        // Landing after the group's *hidden* members would put the row in the
        // group too, but at the far end where the user cannot see what happened.
        val start = listOf("cacn") + ids.filterNot { it == "cacn" }
        val moved = drag(start, "cacn", down = true)!!
        assertEquals("Local", membership(moved)["cacn"])
        assertEquals(listOf("group-Local", "cacn", "l1", "l2", "l3"), moved.take(5))
    }

    @Test
    fun `no other connection changes group, dragging either way`() {
        val others = ids.filter { !it.startsWith("group-") && it != "cacn" }
        listOf(true, false).forEach { down ->
            var list = ids
            repeat(6) {
                list = drag(list, "cacn", down) ?: return@repeat
                val m = membership(list)
                others.forEach { id ->
                    assertEquals("$id moved group (down=$down)", membership(ids)[id], m[id])
                }
                assertTrue("lost a row", list.toSet() == ids.toSet() && list.size == ids.size)
            }
        }
    }

    @Test
    fun `swapping two connections inside an expanded group is unaffected`() {
        val moved = drag(ids, "cloud", down = false)!!
        assertEquals(listOf("group-Linux", "cloud", "cacn", "win"), moved.takeLast(4))
        assertEquals("Linux", membership(moved)["cloud"])
    }

    @Test
    fun `there is no move past either end`() {
        assertNull(drag(ids, "win", down = true))
        // group-Local is the first row, so the row after it has nothing above.
        val top = listOf("cacn") + ids.filterNot { it == "cacn" }
        assertNull(drag(top, "cacn", down = false))
    }

    /**
     * A connection row is roughly twice the height of a group header, so the
     * two candidate measurements differ and the wrong one is visible as the row
     * sliding away from the finger.
     */
    @Test
    fun `moving down measures the neighbour's height, not the dragged row's`() {
        // Connection at 1000 (200 tall), group header below it at 1200 (120 tall).
        val step = neighbourBlockHeight(
            draggedOffset = 1000,
            neighbourOffset = 1200,
            neighbourSize = 120,
            afterNeighbourOffset = 1320,
            down = true,
        )
        // The slot moves up by the header's height. Measuring the gap instead
        // would give 200 — the dragged row's own height — and over-correct by 80
        // on every header stepped over.
        assertEquals(120, step)
    }

    @Test
    fun `a neighbour's dependent rows travel with it and count towards the step`() {
        // Neighbour at 1200 is 200 tall with one 160-tall jump host under it, so
        // the next top-level row starts at 1560.
        assertEquals(
            360,
            neighbourBlockHeight(
                draggedOffset = 1000,
                neighbourOffset = 1200,
                neighbourSize = 200,
                afterNeighbourOffset = 1560,
                down = true,
            ),
        )
        // Upwards the dragged row's own offset already bounds the block, so the
        // dependents are counted without needing a third row.
        assertEquals(
            360,
            neighbourBlockHeight(
                draggedOffset = 1360,
                neighbourOffset = 1000,
                neighbourSize = 200,
                afterNeighbourOffset = null,
                down = false,
            ),
        )
    }

    @Test
    fun `the last row has nothing after it to measure against`() {
        assertEquals(
            120,
            neighbourBlockHeight(
                draggedOffset = 1000,
                neighbourOffset = 1200,
                neighbourSize = 120,
                afterNeighbourOffset = null,
                down = true,
            ),
        )
    }

    @Test
    fun `a row that is not on screen does not move`() {
        // l2 is inside a collapsed group and was never rendered, so nothing can
        // be dragging it.
        assertNull(moveDraggedRow(ids, displayedOf(ids), "l2", down = true))
        assertNull(moveDraggedRow(ids, displayedOf(ids), "nosuch", down = true))
    }
}
