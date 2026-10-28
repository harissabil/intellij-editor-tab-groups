package com.github.harissabil.editortabgroups.platform

import com.github.harissabil.editortabgroups.core.SavedTabGroup
import com.github.harissabil.editortabgroups.core.StripSnapshot
import com.github.harissabil.editortabgroups.core.TabGroup
import com.github.harissabil.editortabgroups.core.TabGroupColor
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection

class TabGroupsState {
    @XCollection(style = XCollection.Style.v2)
    var strips: MutableList<StripState> = ArrayList()

    @XCollection(style = XCollection.Style.v2)
    var saved: MutableList<SavedGroupState> = ArrayList()
}

@Tag("strip")
class StripState {
    @XCollection(style = XCollection.Style.v2, elementName = "file")
    var files: MutableList<String> = ArrayList()

    @XCollection(style = XCollection.Style.v2)
    var groups: MutableList<GroupState> = ArrayList()
}

@Tag("group")
class GroupState {
    var id: String = ""
    var label: String = ""
    var color: String = TabGroupColor.BLUE.name
    var collapsed: Boolean = false

    @XCollection(style = XCollection.Style.v2, elementName = "file")
    var files: MutableList<String> = ArrayList()
}

@Tag("saved")
class SavedGroupState {
    var savedAt: Long = 0
    var group: GroupState = GroupState()
}

internal object StateMapper {
    fun toState(group: TabGroup) = GroupState().apply {
        id = group.id
        label = group.label
        color = group.color.name
        collapsed = group.collapsed
        files = group.files.toMutableList()
    }

    fun fromState(state: GroupState): TabGroup? {
        if (state.id.isEmpty()) return null
        val color = TabGroupColor.entries.firstOrNull { it.name == state.color } ?: TabGroupColor.BLUE
        return TabGroup(
            state.id,
            state.label,
            color,
            state.collapsed,
            state.files.filter { it.isNotEmpty() }.distinct()
        )
    }

    fun toState(snapshot: StripSnapshot) = StripState().apply {
        files = snapshot.files.toMutableList()
        groups = snapshot.groups.map(::toState).toMutableList()
    }

    fun fromState(state: StripState) = StripSnapshot(state.files.toList(), state.groups.mapNotNull(::fromState))

    fun toState(saved: SavedTabGroup) = SavedGroupState().apply {
        savedAt = saved.savedAt
        group = toState(saved.group)
    }

    fun fromState(state: SavedGroupState): SavedTabGroup? =
        fromState(state.group)?.let { SavedTabGroup(it, state.savedAt) }
}
