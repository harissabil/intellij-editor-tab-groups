package com.github.harissabil.editortabgroups.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TabGroupModelTest {
    private var ids = 0
    private var now = 1000L
    private val model = TabGroupModel(newId = { "g${++ids}" }, clock = { now }, random = { 0 })

    private fun create(vararg files: String) = model.createGroup(files.toList())!!

    // ---- create ----

    @Test
    fun `new group has empty label, is expanded and gets first unused color`() {
        val g = create("a", "b")
        assertEquals("", g.label)
        assertFalse(g.collapsed)
        assertEquals(TabGroupColor.BLUE, g.color)
        assertEquals(listOf("a", "b"), g.files)
        assertEquals(TabGroupColor.PURPLE, create("c").color)
    }

    @Test
    fun `color falls back to random pick when all are used`() {
        TabGroupColor.entries.forEachIndexed { i, _ -> create("f$i") }
        assertEquals(TabGroupColor.entries[0], model.nextColor())
    }

    @Test
    fun `color is reused once its group is gone`() {
        val blue = create("a")
        create("b")
        model.ungroup(blue.id)
        assertEquals(TabGroupColor.BLUE, model.nextColor())
    }

    @Test
    fun `creating from a single tab works`() {
        assertEquals(listOf("a"), create("a").files)
    }

    @Test
    fun `pinned files are not grouped`() {
        assertNull(model.createGroup(listOf("p"), pinned = setOf("p")))
        assertEquals(listOf("a"), model.createGroup(listOf("p", "a"), pinned = setOf("p"))!!.files)
    }

    @Test
    fun `grouping a file that is in another group moves it and empties the old group`() {
        val old = create("a")
        val new = create("a", "b")
        assertNull(model.group(old.id))
        assertEquals(new.id, model.groupOf("a")!!.id)
    }

    // ---- membership ----

    @Test
    fun `adding appends to the end of the group`() {
        val g = create("a", "b")
        model.addToGroup(g.id, listOf("c"))
        assertEquals(listOf("a", "b", "c"), model.group(g.id)!!.files)
        assertEquals(listOf("x", "a", "b", "c", "y"), model.arrange(listOf("x", "c", "a", "b", "y")))
    }

    @Test
    fun `remove from group places the tab right after the group`() {
        val g = create("a", "b", "c")
        val strip = model.removeFromGroup(listOf("a"), listOf("x", "a", "b", "c", "y"))
        assertEquals(listOf("x", "b", "c", "a", "y"), strip)
        assertEquals(listOf("b", "c"), model.group(g.id)!!.files)
    }

    @Test
    fun `removing the only member removes the group`() {
        val g = create("a")
        model.removeFromGroup(listOf("a"), listOf("a"))
        assertNull(model.group(g.id))
    }

    @Test
    fun `ungroup keeps files in place and dissolves the group`() {
        val g = create("a", "b")
        assertEquals(listOf("a", "b"), model.ungroup(g.id))
        assertTrue(model.groups.isEmpty())
        assertEquals(listOf("x", "a", "b"), model.arrange(listOf("x", "a", "b")))
    }

    @Test
    fun `pinning a member takes it out of the group`() {
        val g = create("a", "b")
        model.onPinned("a")
        assertEquals(listOf("b"), model.group(g.id)!!.files)
    }

    @Test
    fun `rename and recolor`() {
        val g = create("a")
        model.rename(g.id, "Backend")
        model.setColor(g.id, TabGroupColor.RED)
        assertEquals("Backend", model.group(g.id)!!.label)
        assertEquals(TabGroupColor.RED, model.group(g.id)!!.color)
    }

    // ---- contiguity ----

    @Test
    fun `arrange makes group members contiguous at the first member`() {
        create("a", "b", "c")
        assertEquals(listOf("x", "a", "b", "c", "y", "z"), model.arrange(listOf("x", "a", "y", "b", "z", "c")))
    }

    @Test
    fun `arrange uses group order, not strip order`() {
        create("a", "b")
        assertEquals(listOf("a", "b", "x"), model.arrange(listOf("b", "x", "a")))
    }

    @Test
    fun `newly opened file inside a group run is pushed after the group`() {
        create("a", "b")
        assertEquals(listOf("a", "b", "new"), model.arrange(listOf("a", "new", "b")))
    }

    @Test
    fun `arrange keeps pinned tabs first and ignores members missing from the strip`() {
        create("a", "b", "c")
        assertEquals(listOf("p", "x", "a", "c"), model.arrange(listOf("p", "x", "c", "a"), pinned = setOf("p")))
    }

    // ---- drag ----

    @Test
    fun `dragging a tab between two members adds it to the group`() {
        val g = create("a", "b")
        model.onTabMoved("x", listOf("a", "x", "b"))
        assertEquals(listOf("a", "x", "b"), model.group(g.id)!!.files)
    }

    @Test
    fun `dragging a member away from its group removes it`() {
        val g = create("a", "b", "c")
        model.onTabMoved("b", listOf("a", "c", "y", "b"))
        assertEquals(listOf("a", "c"), model.group(g.id)!!.files)
        assertNull(model.groupOf("b"))
    }

    @Test
    fun `dragging a member just past the last member keeps it while it touches the group`() {
        val g = create("a", "b", "c")
        model.onTabMoved("a", listOf("b", "c", "a", "y"))
        assertEquals(listOf("b", "c", "a"), model.group(g.id)!!.files)
    }

    @Test
    fun `dropping a non member right after the last member does not join`() {
        create("a", "b")
        model.onTabMoved("x", listOf("a", "b", "x"))
        assertNull(model.groupOf("x"))
    }

    @Test
    fun `reorder within group updates group order`() {
        val g = create("a", "b", "c")
        model.onTabMoved("c", listOf("c", "a", "b"))
        assertEquals(listOf("c", "a", "b"), model.group(g.id)!!.files)
    }

    @Test
    fun `dragging a member into another group moves it there`() {
        val g1 = create("a", "b")
        val g2 = create("c", "d")
        model.onTabMoved("a", listOf("b", "c", "a", "d"))
        assertEquals(listOf("b"), model.group(g1.id)!!.files)
        assertEquals(listOf("c", "a", "d"), model.group(g2.id)!!.files)
    }

    @Test
    fun `moving a whole group keeps it together and never splits another group`() {
        val g = create("a", "b")
        create("c", "d")
        assertEquals(
            listOf("x", "c", "d", "a", "b"),
            model.moveGroup(g.id, listOf("a", "b", "x", "c", "d"), beforeFile = null)
        )
        assertEquals(
            listOf("x", "a", "b", "c", "d"),
            model.moveGroup(g.id, listOf("a", "b", "x", "c", "d"), beforeFile = "d")
        )
    }

    @Test
    fun `moving a group never lands among pinned tabs`() {
        val g = create("a")
        assertEquals(
            listOf("p", "a", "x"),
            model.moveGroup(g.id, listOf("p", "x", "a"), beforeFile = "p", pinned = setOf("p"))
        )
    }

    // ---- collapse ----

    @Test
    fun `collapsed group shows only the carrier`() {
        val g = create("a", "b", "c")
        model.toggleCollapsed(g.id)
        val strip = listOf("x", "a", "b", "c")
        assertEquals("a", model.carrier(g.id, strip))
        assertEquals(setOf("b", "c"), model.hiddenFiles(strip, selected = "x"))
    }

    @Test
    fun `collapsing keeps the selected member visible with overflow count`() {
        val g = create("a", "b", "c")
        model.setCollapsed(g.id, true)
        val strip = listOf("a", "b", "c")
        assertEquals(setOf("c"), model.hiddenFiles(strip, selected = "b"))
        assertEquals(2, model.overflowCount(g.id, strip, selected = "b"))
        assertEquals(0, model.overflowCount(g.id, strip, selected = "x"))
    }

    @Test
    fun `selecting another tab hides the previously selected member`() {
        val g = create("a", "b", "c")
        model.setCollapsed(g.id, true)
        val strip = listOf("x", "a", "b", "c")
        assertFalse("c" in model.hiddenFiles(strip, selected = "c"))
        assertTrue("c" in model.hiddenFiles(strip, selected = "x"))
    }

    @Test
    fun `expanded group hides nothing`() {
        create("a", "b")
        assertTrue(model.hiddenFiles(listOf("a", "b"), selected = null).isEmpty())
    }

    @Test
    fun `carrier is per strip`() {
        val g = create("a", "b", "c")
        assertEquals("b", model.carrier(g.id, listOf("x", "b", "c")))
    }

    // ---- closing ----

    @Test
    fun `closing the last tab one by one removes the group without saving`() {
        val g = create("a", "b")
        assertTrue(model.onFilesClosed(listOf("a")).isEmpty())
        assertEquals(listOf("b"), model.group(g.id)!!.files)
        assertTrue(model.onFilesClosed(listOf("b")).isEmpty())
        assertNull(model.group(g.id))
    }

    @Test
    fun `closing all tabs of a group in one operation saves it`() {
        val g = create("a", "b")
        now = 5000
        assertEquals(listOf(SavedTabGroup(g, 5000)), model.onFilesClosed(listOf("a", "b", "x")))
        assertNull(model.group(g.id))
    }

    @Test
    fun `closing some tabs of a group in one operation keeps the rest`() {
        val g = create("a", "b", "c")
        assertTrue(model.onFilesClosed(listOf("a", "b", "x")).isEmpty())
        assertEquals(listOf("c"), model.group(g.id)!!.files)
    }

    @Test
    fun `delete group returns files to close and does not save`() {
        val g = create("a", "b")
        assertEquals(listOf("a", "b"), model.deleteGroup(g.id))
        assertNull(model.group(g.id))
    }

    @Test
    fun `save and close returns a saved copy, restore brings it back expanded`() {
        val g = create("a", "b")
        model.setCollapsed(g.id, true)
        val saved = model.saveAndClose(g.id)!!
        assertEquals(listOf("a", "b"), saved.group.files)
        assertTrue(model.groups.isEmpty())
        val restored = model.restore(saved)
        assertEquals(listOf("a", "b"), restored.files)
        assertFalse(restored.collapsed)
        assertEquals(g.id, restored.id)
    }

    @Test
    fun `restoring takes files out of groups they joined meanwhile`() {
        val g = create("a", "b")
        val saved = model.saveAndClose(g.id)!!
        val other = create("a", "c")
        model.restore(saved)
        assertEquals(listOf("c"), model.group(other.id)!!.files)
    }

    @Test
    fun `closing the strip saves all its groups`() {
        create("a")
        create("b")
        assertEquals(2, model.closeAll().size)
        assertTrue(model.groups.isEmpty())
    }

    @Test
    fun `colors used in other strips are skipped`() {
        val m = TabGroupModel(newId = { "x" }, usedElsewhere = { setOf(TabGroupColor.BLUE, TabGroupColor.PURPLE) })
        assertEquals(TabGroupColor.CYAN, m.nextColor())
    }

    // ---- persistence robustness ----

    @Test
    fun `missing files are dropped and empty groups vanish`() {
        val g1 = create("a", "gone")
        val g2 = create("gone2")
        model.retainFiles { !it.startsWith("gone") }
        assertEquals(listOf("a"), model.group(g1.id)!!.files)
        assertNull(model.group(g2.id))
    }

    @Test
    fun `renamed file keeps its membership`() {
        val g = create("a", "b")
        model.renameFile("a", "a2")
        assertEquals(listOf("a2", "b"), model.group(g.id)!!.files)
    }

    @Test
    fun `load drops duplicate membership and empty groups`() {
        model.load(
            listOf(
                TabGroup("1", "", TabGroupColor.BLUE, false, listOf("a", "b")),
                TabGroup("2", "", TabGroupColor.RED, true, listOf("b", "c")),
                TabGroup("3", "", TabGroupColor.RED, true, emptyList()),
            ),
        )
        assertEquals(listOf("a", "b"), model.group("1")!!.files)
        assertEquals(listOf("c"), model.group("2")!!.files)
        assertNull(model.group("3"))
    }

    @Test
    fun `findMoved detects a single moved tab`() {
        assertEquals("c", TabGroupModel.findMoved(listOf("a", "b", "c"), listOf("c", "a", "b")))
        assertEquals("a", TabGroupModel.findMoved(listOf("a", "b", "c"), listOf("b", "c", "a")))
        assertNull(TabGroupModel.findMoved(listOf("a", "b"), listOf("a", "b")))
        assertNull(TabGroupModel.findMoved(listOf("a", "b", "c", "d"), listOf("b", "a", "d", "c")))
    }

    @Test
    fun `applyVisibleMove keeps hidden tabs in place`() {
        // b and c hidden; e dragged before a
        val (order, moved) = TabGroupModel.applyVisibleMove(
            listOf("a", "b", "c", "d", "e"),
            visibleBefore = listOf("a", "d", "e"),
            visibleAfter = listOf("e", "a", "d"),
        )!!
        assertEquals("e", moved)
        assertEquals(listOf("e", "a", "b", "c", "d"), order)
    }

    @Test
    fun `applyVisibleMove places a tab dropped last after its new left neighbor`() {
        val (order, _) = TabGroupModel.applyVisibleMove(
            listOf("a", "b", "c"),
            visibleBefore = listOf("a", "b", "c"),
            visibleAfter = listOf("b", "c", "a"),
        )!!
        assertEquals(listOf("b", "c", "a"), order)
    }
}
