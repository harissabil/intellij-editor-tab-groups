package com.github.harissabil.editortabgroups.platform

import com.github.harissabil.editortabgroups.core.SavedTabGroup
import com.github.harissabil.editortabgroups.core.StripSnapshot
import com.github.harissabil.editortabgroups.core.TabGroup
import com.github.harissabil.editortabgroups.core.TabGroupColor
import com.intellij.util.xmlb.XmlSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TabGroupsStateTest {
    @Test
    fun `state survives xml round trip`() {
        val strip = StripSnapshot(
            listOf("file:///a", "file:///b", "file:///c"),
            listOf(TabGroup("g1", "Backend", TabGroupColor.GREEN, true, listOf("file:///b", "file:///c"))),
        )
        val saved = SavedTabGroup(TabGroup("g2", "", TabGroupColor.RED, false, listOf("file:///x")), 42)
        val state = TabGroupsState().apply {
            strips.add(StateMapper.toState(strip))
            this.saved.add(StateMapper.toState(saved))
        }

        val element = XmlSerializer.serialize(state)
        val back = XmlSerializer.deserialize(element, TabGroupsState::class.java)

        assertEquals(listOf(strip), back.strips.map(StateMapper::fromState))
        assertEquals(listOf(saved), back.saved.mapNotNull(StateMapper::fromState))
    }

    @Test
    fun `unknown color falls back and empty ids are dropped`() {
        val g = GroupState().apply { id = "g"; color = "MAGENTA"; files = mutableListOf("file:///a", "", "file:///a") }
        assertEquals(TabGroup("g", "", TabGroupColor.BLUE, false, listOf("file:///a")), StateMapper.fromState(g))
        assertNull(StateMapper.fromState(GroupState()))
    }
}
