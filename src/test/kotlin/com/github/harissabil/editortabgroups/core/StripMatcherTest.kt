package com.github.harissabil.editortabgroups.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StripMatcherTest {
    private fun group(vararg files: String) = TabGroup("g", "", TabGroupColor.BLUE, false, files.toList())

    @Test
    fun `each snapshot goes to the live strip with most shared files`() {
        val snapshots = listOf(
            StripSnapshot(listOf("a", "b", "c"), listOf(group("b", "c"))),
            StripSnapshot(listOf("x", "y"), listOf(group("x", "y"))),
        )
        val live = listOf("w1" to listOf("x", "y", "a"), "w2" to listOf("a", "b", "c"))
        assertEquals(mapOf(0 to "w2", 1 to "w1"), StripMatcher.match(snapshots, live))
    }

    @Test
    fun `one live strip takes at most one snapshot`() {
        val snapshots = listOf(StripSnapshot(listOf("a", "b"), emptyList()), StripSnapshot(listOf("a"), emptyList()))
        val live = listOf("w1" to listOf("a", "b"))
        assertEquals(mapOf(0 to "w1"), StripMatcher.match(snapshots, live))
    }

    @Test
    fun `no shared file means no match`() {
        assertTrue(
            StripMatcher.match(listOf(StripSnapshot(listOf("a"), emptyList())), listOf("w" to listOf("z"))).isEmpty()
        )
    }

    @Test
    fun `group files count even if the snapshot file list is empty`() {
        val snapshots = listOf(StripSnapshot(emptyList(), listOf(group("a"))))
        assertEquals(mapOf(0 to "w"), StripMatcher.match(snapshots, listOf("w" to listOf("a"))))
    }
}

class SavedGroupsTest {
    private fun saved(id: String, vararg files: String, at: Long = 0) =
        SavedTabGroup(TabGroup(id, "", TabGroupColor.BLUE, false, files.toList()), at)

    @Test
    fun `newest first, re-adding replaces`() {
        val s = SavedGroups()
        s.add(saved("1", "a"))
        s.add(saved("2", "b"))
        s.add(saved("1", "a", "c"))
        assertEquals(listOf("1", "2"), s.all.map { it.group.id })
        assertEquals(listOf("a", "c"), s.all[0].group.files)
    }

    @Test
    fun `take removes, forget removes, empty groups are not kept`() {
        val s = SavedGroups()
        s.add(saved("1", "a"))
        s.add(saved("2", "b"))
        s.add(saved("3"))
        assertEquals("1", s.take("1")!!.group.id)
        s.forget("2")
        assertTrue(s.all.isEmpty())
    }

    @Test
    fun `missing files are dropped`() {
        val s = SavedGroups()
        s.add(saved("1", "a", "gone"))
        s.add(saved("2", "gone"))
        s.retainFiles { it != "gone" }
        assertEquals(listOf(listOf("a")), s.all.map { it.group.files })
    }

    @Test
    fun `rename keeps membership`() {
        val s = SavedGroups()
        s.add(saved("1", "a"))
        s.renameFile("a", "b")
        assertEquals(listOf("b"), s.all[0].group.files)
    }
}
