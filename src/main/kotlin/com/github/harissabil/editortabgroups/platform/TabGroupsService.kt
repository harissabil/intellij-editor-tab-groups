package com.github.harissabil.editortabgroups.platform

import com.github.harissabil.editortabgroups.core.SavedGroups
import com.github.harissabil.editortabgroups.core.SavedTabGroup
import com.github.harissabil.editortabgroups.core.StripMatcher
import com.github.harissabil.editortabgroups.core.StripSnapshot
import com.github.harissabil.editortabgroups.core.TabGroup
import com.github.harissabil.editortabgroups.core.TabGroupColor
import com.github.harissabil.editortabgroups.core.TabGroupModel
import com.github.harissabil.editortabgroups.ui.GroupChip
import com.github.harissabil.editortabgroups.ui.GroupEditorPopup
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.ex.FileEditorOpenRequest
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.util.concurrency.annotations.RequiresEdt
import java.awt.AWTEvent
import java.awt.Point
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import javax.swing.Timer

/**
 * Tab groups of a project, one [TabGroupModel] per editor window. EDT only.
 *
 * Editor windows have no identity across restarts, so saved strips are matched to restored windows by
 * shared files ([StripMatcher]). Whatever is still unmatched once restoring settles becomes saved groups.
 */
private val LOG = logger<TabGroupsService>()

@Service(Service.Level.PROJECT)
@State(name = "EditorTabGroups", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class TabGroupsService(private val project: Project) : PersistentStateComponent<TabGroupsState>, Disposable {
    private inner class Strip(val window: EditorWindow) {
        val model = TabGroupModel(usedElsewhere = ::usedColors)
        val adapter = TabStripAdapter(window, adapterEvents, ChipCallbacks(window))
        var order: List<String> = emptyList()
        var visible: List<String> = emptyList()
        val closed = ArrayList<String>()
    }

    private val strips = LinkedHashMap<EditorWindow, Strip>()
    private val saved = SavedGroups()
    private val pending = ArrayList<StripSnapshot>()
    private var settled = false
    private var syncQueued = false
    private var groupDrag: Pair<EditorWindow, String>? = null
    private var groupDragHandled = false

    // getState can be called off the EDT
    @Volatile
    private var stateSnapshot = TabGroupsState()

    // Touching the tab strip during a tab drag breaks the drag, so syncing waits for the mouse release.
    private var mouseDown = false

    private val settleTimer = Timer(4000) { settle() }.apply { isRepeats = false }

    init {
        val connection = project.messageBus.connect(this)
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                if (!settled) settleTimer.restart()
                queueSync()
            }

            override fun fileClosed(source: FileEditorManager, file: VirtualFile) = queueSync()
            override fun selectionChanged(event: FileEditorManagerEvent) = queueSync()
        })
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val renames = events.mapNotNull(::renameOf)
                if (renames.isNotEmpty()) runOnEdt { renames.forEach { (old, new) -> rekey(old, new) } }
            }
        })
        val mouseListener = AWTEventListener { e ->
            val down = (e as? MouseEvent)?.modifiersEx?.and(BUTTONS_DOWN) ?: 0
            val wasDown = mouseDown
            mouseDown = down != 0
            if (wasDown && !mouseDown) queueSync()
        }
        Toolkit.getDefaultToolkit().addAWTEventListener(
            mouseListener,
            AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_MOTION_EVENT_MASK,
        )
        settleTimer.start()
        Disposer.register(this) {
            settleTimer.stop()
            Toolkit.getDefaultToolkit().removeAWTEventListener(mouseListener)
        }
    }

    fun groups(window: EditorWindow): List<TabGroup> = strip(window)?.model?.groups ?: emptyList()

    fun groupOf(window: EditorWindow, file: VirtualFile): TabGroup? = strip(window)?.model?.groupOf(file.url)

    fun savedGroups(): List<SavedTabGroup> = saved.all

    internal fun chip(window: EditorWindow, groupId: String): GroupChip? = strips[window]?.adapter?.chip(groupId)

    /** Returns the new group's id, or null if all [files] are pinned. */
    @RequiresEdt
    fun createGroup(window: EditorWindow, files: List<VirtualFile>): String? {
        val s = strip(window) ?: return null
        val group = s.model.createGroup(files.map { it.url }, pinnedKeys(s)) ?: return null
        sync()
        return group.id
    }

    @RequiresEdt
    fun addToGroup(window: EditorWindow, groupId: String, files: List<VirtualFile>) {
        val s = strip(window) ?: return
        s.model.addToGroup(groupId, files.map { it.url }, pinnedKeys(s))
        sync()
    }

    @RequiresEdt
    fun removeFromGroup(window: EditorWindow, files: List<VirtualFile>) {
        val s = strip(window) ?: return
        applyOrder(s, s.model.removeFromGroup(files.map { it.url }, s.adapter.files().map { it.url }))
        sync()
    }

    @RequiresEdt
    fun toggleCollapsed(window: EditorWindow, groupId: String) = edit(window) { it.toggleCollapsed(groupId) }

    @RequiresEdt
    fun setCollapsed(window: EditorWindow, groupId: String, collapsed: Boolean) =
        edit(window) { it.setCollapsed(groupId, collapsed) }

    @RequiresEdt
    fun rename(window: EditorWindow, groupId: String, label: String) = edit(window) { it.rename(groupId, label) }

    @RequiresEdt
    fun setColor(window: EditorWindow, groupId: String, color: TabGroupColor) =
        edit(window) { it.setColor(groupId, color) }

    @RequiresEdt
    fun ungroup(window: EditorWindow, groupId: String) = edit(window) { it.ungroup(groupId) }

    @RequiresEdt
    fun deleteGroup(window: EditorWindow, groupId: String) {
        val s = strip(window) ?: return
        close(window, s.model.deleteGroup(groupId))
        sync()
    }

    @RequiresEdt
    fun saveAndCloseGroup(window: EditorWindow, groupId: String) {
        val s = strip(window) ?: return
        val copy = s.model.saveAndClose(groupId) ?: return
        saved.add(copy)
        close(window, copy.group.files)
        sync()
    }

    @RequiresEdt
    fun reopenSaved(window: EditorWindow, groupId: String) {
        val s = strip(window) ?: return
        val copy = saved.take(groupId) ?: return
        val files = copy.group.files
            .mapNotNull { VirtualFileManager.getInstance().findFileByUrl(it) }
            .filter { it.isValid }
        if (files.isEmpty()) return
        s.model.restore(copy.copy(group = copy.group.copy(files = files.map { it.url })))
        val manager = FileEditorManagerEx.getInstanceEx(project)
        for (file in files.reversed()) {
            manager.openFile(file, FileEditorOpenRequest(targetWindow = window, selectAsCurrent = false))
        }
        manager.openFile(files.first(), FileEditorOpenRequest(targetWindow = window, selectAsCurrent = true))
        sync()
    }

    @RequiresEdt
    fun forgetSaved(groupId: String) {
        saved.forget(groupId)
        stateSnapshot = buildState()
    }

    private fun strip(window: EditorWindow): Strip? {
        if (window !in strips) sync()
        return strips[window]
    }

    private inline fun edit(window: EditorWindow, change: (TabGroupModel) -> Unit) {
        val s = strip(window) ?: return
        change(s.model)
        sync()
    }

    private fun queueSync() {
        if (syncQueued) return
        syncQueued = true
        ApplicationManager.getApplication().invokeLater({ sync(force = false) }, ModalityState.any(), project.disposed)
    }

    @RequiresEdt
    fun sync() = sync(force = true)

    private fun sync(force: Boolean) {
        syncQueued = false
        if (project.isDisposed || !force && mouseDown) return
        val live = FileEditorManagerEx.getInstanceEx(project).windows.filter { !it.isDisposed }

        for ((window, s) in strips.toList()) {
            if (window !in live) {
                // Like Firefox closing a window: the groups are kept as saved groups.
                saved.addAll(s.model.closeAll())
                Disposer.dispose(s.adapter)
                strips.remove(window)
            }
        }
        for (window in live) {
            if (window !in strips) {
                val s = Strip(window)
                Disposer.register(this, s.adapter)
                strips[window] = s
            }
        }
        if (pending.isNotEmpty()) matchPending()
        strips.values.forEach(::syncStrip)
        stateSnapshot = buildState()
    }

    private fun syncStrip(s: Strip) {
        if (!s.adapter.isAlive) return
        if (s.closed.isNotEmpty()) {
            saved.addAll(s.model.onFilesClosed(s.closed))
            s.closed.clear()
        }
        val files = s.adapter.files()
        val byKey = files.associateBy { it.url }
        val pinned = s.adapter.pinnedFiles().map { it.url }.toSet()
        pinned.forEach { if (s.model.groupOf(it) != null) s.model.onPinned(it) }

        val keys = files.map { it.url }
        val order = s.model.arrange(keys, pinned)
        if (order != keys) {
            LOG.debug { "sync reorders strip: $keys -> $order" }
            s.adapter.reorder(order.mapNotNull(byKey::get))
        }

        val selected = s.adapter.selectedFile()?.url
        val chips = s.model.groups.mapNotNull { g ->
            val carrier = s.model.carrier(g.id, order) ?: return@mapNotNull null
            ChipView(
                groupId = g.id,
                label = g.label,
                color = g.color,
                collapsed = g.collapsed,
                carrier = byKey.getValue(carrier),
                members = g.files.mapNotNull(byKey::get).toSet(),
                compact = g.collapsed && carrier != selected,
                overflow = s.model.overflowCount(g.id, order, selected),
            )
        }
        val hidden = s.model.hiddenFiles(order, selected)
        s.adapter.render(StripView(hidden.mapNotNull(byKey::get).toSet(), chips))
        s.order = order
        s.visible = order.filter { it !in hidden }
    }

    private fun applyOrder(s: Strip, order: List<String>) {
        val byKey = s.adapter.files().associateBy { it.url }
        s.adapter.reorder(order.mapNotNull(byKey::get))
    }

    private fun matchPending() {
        val candidates = strips.values.filter { it.model.groups.isEmpty() && it.adapter.files().isNotEmpty() }
        if (candidates.isEmpty()) return
        val live = candidates.mapIndexed { i, s -> i.toString() to s.adapter.files().map { it.url } }
        val matches = StripMatcher.match(pending, live)
        for ((snapshotIndex, liveId) in matches) {
            candidates[liveId.toInt()].model.load(pending[snapshotIndex].groups)
        }
        val rest = pending.filterIndexed { i, _ -> i !in matches.keys }
        pending.clear()
        pending.addAll(rest)
    }

    private fun settle() {
        if (settled || project.isDisposed) return
        sync()
        settled = true
        val now = System.currentTimeMillis()
        for (snapshot in pending) saved.addAll(snapshot.groups.map { SavedTabGroup(it.copy(collapsed = false), now) })
        pending.clear()
        for (s in strips.values) {
            val open = s.adapter.files().map { it.url }.toSet()
            for (g in s.model.groups) {
                if (g.files.none { it in open }) {
                    s.model.ungroup(g.id)
                    saved.add(SavedTabGroup(g, now))
                }
            }
            s.model.retainFiles { it in open }
        }
        saved.retainFiles { VirtualFileManager.getInstance().findFileByUrl(it) != null }
        sync()
    }

    private fun close(window: EditorWindow, keys: List<String>) {
        val manager = FileEditorManagerEx.getInstanceEx(project)
        val byKey = strips[window]?.adapter?.files()?.associateBy { it.url } ?: return
        for (key in keys) byKey[key]?.let { manager.closeFile(it, window) }
    }

    private fun pinnedKeys(s: Strip): Set<String> = s.adapter.pinnedFiles().map { it.url }.toSet()

    private fun usedColors(): Set<TabGroupColor> =
        strips.values.flatMap { s -> s.model.groups.map { it.color } }.toSet()

    private val adapterEvents: TabStripAdapter.Events = object : TabStripAdapter.Events {
        override fun selectionChanged(strip: TabStripAdapter) = queueSync()

        override fun tabRemoved(strip: TabStripAdapter, file: VirtualFile) {
            LOG.debug { "tabRemoved: ${file.url}" }
            strips[strip.window]?.closed?.add(file.url)
            queueSync()
        }

        override fun tabsMoved(strip: TabStripAdapter) {
            val s = strips[strip.window] ?: return
            val pinned = pinnedKeys(s)
            val visibleNow = s.adapter.visibleFiles().map { it.url }
            // the tab being dragged was selected when it was pressed
            val dragged = s.adapter.selectedFile()?.url
            val move = TabGroupModel.applyVisibleMove(s.order, s.visible, visibleNow, dragged)
            LOG.debug { "tabsMoved: before=${s.visible} after=$visibleNow dragged=$dragged move=$move" }
            val (order, moved) = move ?: run {
                sync()
                return
            }
            val drag = groupDrag
            if (drag != null && drag.first == s.window && moved == s.model.carrier(drag.second, s.order)) {
                // The chip's tab was dragged along with the chip: move the whole group.
                groupDragHandled = true
                val group = s.model.group(drag.second)
                val after = order.drop(order.indexOf(moved) + 1).firstOrNull { group == null || it !in group.files }
                applyOrder(s, s.model.moveGroup(drag.second, order, after, pinned))
            } else {
                s.model.onTabMoved(moved, order, pinned, oldStrip = s.order)
                applyOrder(s, s.model.arrange(order, pinned))
            }
            sync()
        }
    }

    private inner class ChipCallbacks(private val window: EditorWindow) : GroupChip.Callbacks {
        override fun toggle(chip: GroupChip) = toggleCollapsed(window, chip.groupId)

        override fun showMenu(chip: GroupChip) =
            GroupEditorPopup.show(project, window, chip.groupId, chip, createMode = false)

        override fun dragStarted(chip: GroupChip) {
            groupDrag = window to chip.groupId
            groupDragHandled = false
        }

        override fun dragFinished(chip: GroupChip, screenPoint: Point) {
            // A tabsMoved from the same mouse release must be handled first.
            ApplicationManager.getApplication().invokeLater({
                if (!groupDragHandled) dropGroup(window, chip.groupId, screenPoint)
                groupDrag = null
            }, ModalityState.any(), project.disposed)
        }
    }

    private fun dropGroup(window: EditorWindow, groupId: String, screenPoint: Point) {
        val s = strips[window] ?: return
        val (file, leftHalf) = s.adapter.tabAt(screenPoint) ?: return
        val key = file.url
        val group = s.model.group(groupId) ?: return
        if (key in group.files) return
        val order = s.order
        val before = if (leftHalf) key else order.drop(order.indexOf(key) + 1).firstOrNull { it !in group.files }
        applyOrder(s, s.model.moveGroup(groupId, order, before, pinnedKeys(s)))
        sync()
    }

    private fun renameOf(event: VFileEvent): Pair<String, String>? {
        val protocol = event.fileSystem.protocol
        return when {
            event is VFilePropertyChangeEvent && event.isRename -> event.oldPath to event.newPath
            event is VFileMoveEvent -> event.oldPath to event.newPath
            else -> null
        }?.let { (old, new) ->
            VirtualFileManager.constructUrl(protocol, old) to VirtualFileManager.constructUrl(protocol, new)
        }
    }

    private fun rekey(oldUrl: String, newUrl: String) {
        fun mapped(key: String): String? = when {
            key == oldUrl -> newUrl
            key.startsWith("$oldUrl/") -> newUrl + key.removePrefix(oldUrl)
            else -> null
        }
        for (s in strips.values) {
            for (key in s.model.groups.flatMap { it.files }) mapped(key)?.let { s.model.renameFile(key, it) }
        }
        for (key in saved.all.flatMap { it.group.files }) mapped(key)?.let { saved.renameFile(key, it) }
        queueSync()
    }

    private fun runOnEdt(action: () -> Unit) {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) action() else app.invokeLater(action, ModalityState.any(), project.disposed)
    }

    override fun getState(): TabGroupsState = stateSnapshot

    private fun buildState(): TabGroupsState {
        val state = TabGroupsState()
        for (s in strips.values) {
            if (s.model.groups.isEmpty()) continue
            state.strips.add(StateMapper.toState(StripSnapshot(s.order, s.model.groups)))
        }
        pending.forEach { state.strips.add(StateMapper.toState(it)) }
        saved.all.forEach { state.saved.add(StateMapper.toState(it)) }
        return state
    }

    override fun loadState(state: TabGroupsState) {
        pending.clear()
        pending.addAll(state.strips.map(StateMapper::fromState).filter { it.groups.isNotEmpty() })
        saved.load(state.saved.mapNotNull(StateMapper::fromState))
        stateSnapshot = buildState()
    }

    override fun dispose() {
        strips.clear()
    }

    companion object {
        private const val BUTTONS_DOWN =
            MouseEvent.BUTTON1_DOWN_MASK or MouseEvent.BUTTON2_DOWN_MASK or MouseEvent.BUTTON3_DOWN_MASK

        fun getInstance(project: Project): TabGroupsService = project.service()
    }
}
