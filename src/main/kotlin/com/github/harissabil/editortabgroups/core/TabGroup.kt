package com.github.harissabil.editortabgroups.core

/** In the order defaults are picked, as in Firefox. */
enum class TabGroupColor {
    BLUE, PURPLE, CYAN, ORANGE, YELLOW, PINK, GREEN, GRAY, RED;
}

/** [files] are file keys (VirtualFile URLs) in strip order. [label] may be empty. */
data class TabGroup(
    val id: String,
    val label: String,
    val color: TabGroupColor,
    val collapsed: Boolean,
    val files: List<String>,
)

data class SavedTabGroup(
    val group: TabGroup,
    val savedAt: Long,
)
