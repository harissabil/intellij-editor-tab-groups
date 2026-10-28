package com.github.harissabil.editortabgroups.core

data class StripSnapshot(
    val files: List<String>,
    val groups: List<TabGroup>,
)

/** Matches saved strips to restored editor windows by shared files, largest overlap first. */
object StripMatcher {
    /** Returns snapshot index to live strip id. */
    fun match(snapshots: List<StripSnapshot>, live: List<Pair<String, List<String>>>): Map<Int, String> {
        data class Candidate(val snapshot: Int, val live: Int, val score: Int)

        val candidates = ArrayList<Candidate>()
        for ((si, snapshot) in snapshots.withIndex()) {
            val keys = (snapshot.files + snapshot.groups.flatMap { it.files }).toSet()
            for ((li, strip) in live.withIndex()) {
                val score = strip.second.count { it in keys }
                if (score > 0) candidates.add(Candidate(si, li, score))
            }
        }
        candidates.sortWith(compareByDescending<Candidate> { it.score }.thenBy { it.snapshot }.thenBy { it.live })

        val result = HashMap<Int, String>()
        val usedLive = HashSet<Int>()
        for (c in candidates) {
            if (c.snapshot in result || c.live in usedLive) continue
            result[c.snapshot] = live[c.live].first
            usedLive.add(c.live)
        }
        return result
    }
}

/** Newest first. */
class SavedGroups {
    private val list = ArrayList<SavedTabGroup>()

    val all: List<SavedTabGroup> get() = list.toList()

    fun add(saved: SavedTabGroup) {
        if (saved.group.files.isEmpty()) return
        list.removeAll { it.group.id == saved.group.id }
        list.add(0, saved)
    }

    fun addAll(saved: Collection<SavedTabGroup>) = saved.forEach(::add)

    fun take(groupId: String): SavedTabGroup? {
        val found = list.firstOrNull { it.group.id == groupId } ?: return null
        list.remove(found)
        return found
    }

    fun forget(groupId: String) {
        list.removeAll { it.group.id == groupId }
    }

    fun retainFiles(exists: (String) -> Boolean) {
        val kept = list.map { it.copy(group = it.group.copy(files = it.group.files.filter(exists))) }
        list.clear()
        list.addAll(kept.filter { it.group.files.isNotEmpty() })
    }

    fun renameFile(oldKey: String, newKey: String) {
        for (i in list.indices) {
            val g = list[i].group
            if (oldKey in g.files) list[i] =
                list[i].copy(group = g.copy(files = g.files.map { if (it == oldKey) newKey else it }))
        }
    }

    fun load(saved: List<SavedTabGroup>) {
        list.clear()
        saved.forEach { if (it.group.files.isNotEmpty()) list.add(it) }
    }
}
