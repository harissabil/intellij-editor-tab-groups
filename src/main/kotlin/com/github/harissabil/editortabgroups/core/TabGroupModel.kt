package com.github.harissabil.editortabgroups.core

/**
 * Tab group rules for one tab strip, following Firefox. Files are string keys and a strip is the
 * ordered list of keys it shows.
 */
class TabGroupModel(
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: (Int) -> Int = { kotlin.random.Random.nextInt(it) },
    private val usedElsewhere: () -> Set<TabGroupColor> = { emptySet() },
) {
    private val _groups = ArrayList<TabGroup>()

    val groups: List<TabGroup> get() = _groups.toList()

    fun group(id: String): TabGroup? = _groups.firstOrNull { it.id == id }

    fun groupOf(file: String): TabGroup? = _groups.firstOrNull { file in it.files }

    fun nextColor(): TabGroupColor {
        val used = _groups.map { it.color }.toSet() + usedElsewhere()
        return TabGroupColor.entries.firstOrNull { it !in used }
            ?: TabGroupColor.entries[random(TabGroupColor.entries.size)]
    }

    /** Pinned files are skipped; Firefox unpins them, but IntelliJ pinning is left alone. */
    fun createGroup(
        files: List<String>,
        pinned: Set<String> = emptySet(),
        label: String = "",
        color: TabGroupColor? = null,
    ): TabGroup? {
        val members = files.distinct().filter { it !in pinned }
        if (members.isEmpty()) return null
        val group = TabGroup(newId(), label, color ?: nextColor(), collapsed = false, files = members)
        detach(members)
        _groups.add(group)
        return group
    }

    fun addToGroup(groupId: String, files: List<String>, pinned: Set<String> = emptySet()) {
        val target = group(groupId) ?: return
        val adding = files.distinct().filter { it !in pinned && it !in target.files }
        if (adding.isEmpty()) return
        detach(adding)
        update(groupId) { it.copy(files = it.files + adding) }
    }

    /** Returns [strip] with the files placed right after their former group. */
    fun removeFromGroup(files: Collection<String>, strip: List<String> = emptyList()): List<String> {
        var result = strip
        for (g in _groups.toList()) {
            val leaving = g.files.filter { it in files }
            if (leaving.isEmpty()) continue
            detach(leaving)
            val remaining = g.files - leaving.toSet()
            val base = result.filter { it !in leaving }
            val anchor = base.indexOfLast { it in remaining }
            val at = if (anchor >= 0) anchor + 1 else result.indexOfFirst { it in leaving }.coerceIn(0, base.size)
            result = base.subList(0, at) + leaving.filter { it in result } + base.subList(at, base.size)
        }
        return result
    }

    fun onPinned(file: String) = detach(listOf(file))

    fun ungroup(groupId: String): List<String> {
        val g = group(groupId) ?: return emptyList()
        _groups.remove(g)
        return g.files
    }

    fun rename(groupId: String, label: String) = update(groupId) { it.copy(label = label) }

    fun setColor(groupId: String, color: TabGroupColor) = update(groupId) { it.copy(color = color) }

    fun setCollapsed(groupId: String, collapsed: Boolean) = update(groupId) { it.copy(collapsed = collapsed) }

    fun toggleCollapsed(groupId: String) = update(groupId) { it.copy(collapsed = !it.collapsed) }

    fun deleteGroup(groupId: String): List<String> = ungroup(groupId)

    fun saveAndClose(groupId: String): SavedTabGroup? {
        val g = group(groupId) ?: return null
        _groups.remove(g)
        return SavedTabGroup(g, clock())
    }

    /**
     * [batch] is one user operation. Like Firefox, a group emptied tab by tab disappears, while a group
     * whose remaining tabs are all closed by one multi-tab close is saved and returned.
     */
    fun onFilesClosed(batch: Collection<String>): List<SavedTabGroup> {
        val closed = batch.toSet()
        if (closed.isEmpty()) return emptyList()
        val toSave = ArrayList<SavedTabGroup>()
        for (g in _groups.toList()) {
            val remaining = g.files.filter { it !in closed }
            when {
                remaining.size == g.files.size -> Unit
                remaining.isEmpty() -> {
                    _groups.remove(g)
                    if (closed.size > 1) toSave.add(SavedTabGroup(g, clock()))
                }

                else -> update(g.id) { it.copy(files = remaining) }
            }
        }
        return toSave
    }

    fun closeAll(): List<SavedTabGroup> {
        val saved = _groups.map { SavedTabGroup(it, clock()) }
        _groups.clear()
        return saved
    }

    fun restore(saved: SavedTabGroup): TabGroup {
        detach(saved.group.files)
        val restored = saved.group.copy(collapsed = false)
        _groups.removeAll { it.id == restored.id }
        _groups.add(restored)
        return restored
    }

    fun retainFiles(keep: (String) -> Boolean) {
        for (g in _groups.toList()) {
            val kept = g.files.filter(keep)
            when {
                kept.isEmpty() -> _groups.remove(g)
                kept.size != g.files.size -> update(g.id) { it.copy(files = kept) }
            }
        }
    }

    fun renameFile(oldKey: String, newKey: String) {
        for (g in _groups.toList()) {
            if (oldKey in g.files) {
                update(g.id) { it.copy(files = it.files.map { f -> if (f == oldKey) newKey else f }) }
            }
        }
    }

    /** Makes each group contiguous, in group order, at the position of its first member. */
    fun arrange(strip: List<String>, pinned: Set<String> = emptySet()): List<String> {
        val present = strip.toSet()
        val result = ArrayList<String>(strip.size)
        val emitted = HashSet<String>()
        result.addAll(strip.filter { it in pinned })
        emitted.addAll(result)
        for (file in strip) {
            if (file in emitted) continue
            val g = groupOf(file)
            if (g == null) {
                result.add(file); emitted.add(file)
            } else {
                for (member in g.files) {
                    if (member in present && member !in pinned && emitted.add(member)) result.add(member)
                }
            }
        }
        return result
    }

    /**
     * Membership after a drag: a member stays while it touches its group, a tab dropped between two
     * members of a group joins it, anything else is ungrouped.
     */
    fun onTabMoved(moved: String, newStrip: List<String>, pinned: Set<String> = emptySet()) {
        val strip = newStrip.filter { it !in pinned }
        val i = strip.indexOf(moved)
        if (i < 0) return
        val prev = strip.getOrNull(i - 1)?.let(::groupOf)
        val next = strip.getOrNull(i + 1)?.let(::groupOf)
        val own = groupOf(moved)
        val target = when {
            own != null && (prev?.id == own.id || next?.id == own.id) -> own
            prev != null && prev.id == next?.id -> prev
            else -> null
        }
        if (target?.id != own?.id) {
            detach(listOf(moved))
            if (target != null) update(target.id) { it.copy(files = it.files + moved) }
        }
        syncOrder(strip)
    }

    fun moveGroup(
        groupId: String,
        strip: List<String>,
        beforeFile: String?,
        pinned: Set<String> = emptySet()
    ): List<String> {
        val g = group(groupId) ?: return strip
        val members = strip.filter { it in g.files }
        val rest = strip.filter { it !in g.files }
        var at = if (beforeFile == null || beforeFile in g.files) rest.size else rest.indexOf(beforeFile)
            .let { if (it < 0) rest.size else it }
        at = maxOf(at, rest.count { it in pinned })
        val other = rest.getOrNull(at)?.let(::groupOf)
        if (other != null) {
            while (at > 0 && groupOf(rest[at - 1])?.id == other.id) at--
        }
        return rest.subList(0, at) + members + rest.subList(at, rest.size)
    }

    fun carrier(groupId: String, strip: List<String>): String? {
        val g = group(groupId) ?: return null
        return strip.firstOrNull { it in g.files }
    }

    /** Collapsed groups hide every member except the first one (it carries the chip) and the selected one. */
    fun hiddenFiles(strip: List<String>, selected: String?): Set<String> {
        val hidden = HashSet<String>()
        for (g in _groups) {
            if (!g.collapsed) continue
            val carrier = carrier(g.id, strip) ?: continue
            for (file in strip) {
                if (file in g.files && file != carrier && file != selected) hidden.add(file)
            }
        }
        return hidden
    }

    fun overflowCount(groupId: String, strip: List<String>, selected: String?): Int {
        val g = group(groupId) ?: return 0
        if (!g.collapsed || selected !in g.files) return 0
        return strip.count { it in g.files && it != selected }
    }

    fun load(groups: List<TabGroup>) {
        _groups.clear()
        val seen = HashSet<String>()
        for (g in groups) {
            val files = g.files.filter { seen.add(it) }
            if (files.isNotEmpty()) _groups.add(g.copy(files = files))
        }
    }

    private fun detach(files: Collection<String>) {
        val set = files.toSet()
        for (g in _groups.toList()) {
            if (g.files.none { it in set }) continue
            val left = g.files.filter { it !in set }
            if (left.isEmpty()) _groups.remove(g) else update(g.id) { it.copy(files = left) }
        }
    }

    private fun syncOrder(strip: List<String>) {
        val pos = strip.withIndex().associate { it.value to it.index }
        for (g in _groups.toList()) {
            val inStrip = g.files.filter { it in pos }.sortedBy { pos.getValue(it) }
            if (inStrip.size < 2) continue
            val elsewhere = g.files.filter { it !in pos }
            update(g.id) { it.copy(files = inStrip + elsewhere) }
        }
    }

    private inline fun update(groupId: String, change: (TabGroup) -> TabGroup) {
        val i = _groups.indexOfFirst { it.id == groupId }
        if (i >= 0) _groups[i] = change(_groups[i])
    }

    companion object {
        fun findMoved(old: List<String>, new: List<String>): String? {
            if (old == new || old.size != new.size || old.toSet() != new.toSet()) return null
            for (candidate in new) {
                if (old.filter { it != candidate } == new.filter { it != candidate }) return candidate
            }
            return null
        }

        /**
         * Applies a single move seen among visible tabs to [order]. The tab strip keeps hidden tabs at
         * stale positions, so the full lists cannot be compared directly.
         */
        fun applyVisibleMove(
            order: List<String>,
            visibleBefore: List<String>,
            visibleAfter: List<String>,
        ): Pair<List<String>, String>? {
            val moved = findMoved(visibleBefore, visibleAfter) ?: return null
            val i = visibleAfter.indexOf(moved)
            val result = order.filter { it != moved }.toMutableList()
            val next = visibleAfter.getOrNull(i + 1)
            val prev = visibleAfter.getOrNull(i - 1)
            val at = when {
                next != null -> result.indexOf(next)
                prev != null -> result.indexOf(prev) + 1
                else -> result.size
            }
            result.add(at.coerceIn(0, result.size), moved)
            return result to moved
        }
    }
}
