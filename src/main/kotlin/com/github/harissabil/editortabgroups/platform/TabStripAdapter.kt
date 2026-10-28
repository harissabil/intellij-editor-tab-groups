package com.github.harissabil.editortabgroups.platform

import com.github.harissabil.editortabgroups.core.TabGroupColor
import com.github.harissabil.editortabgroups.ui.GroupChip
import com.github.harissabil.editortabgroups.ui.OverflowBadge
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.IdeGlassPaneUtil
import com.intellij.ui.tabs.JBTabs
import com.intellij.ui.tabs.JBTabsEx
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.TabsListener
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.Point
import java.util.IdentityHashMap
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.roundToInt

private val LOG = logger<TabStripAdapter>()

private const val ANIMATION_MS = 160

internal data class StripView(
    val hidden: Set<VirtualFile>,
    val chips: List<ChipView>,
)

internal data class ChipView(
    val groupId: String,
    val label: String,
    val color: TabGroupColor,
    val collapsed: Boolean,
    val carrier: VirtualFile,
    val members: Set<VirtualFile>,
    /** The carrier shows only the chip. */
    val compact: Boolean,
    val overflow: Int,
)

/**
 * All access to editor tab strip internals goes through this class.
 *
 * Tested with IntelliJ IDEA 2026.1.4 (IU-261.26222.65) and Android Studio 2026.1.4 (AI-261.26222.65.2614).
 *
 * - Collapsing uses [TabInfo.isHidden], which the platform otherwise only uses during tab drag-out.
 *   Hidden tabs keep their editors open.
 * - The tab label returned by [JBTabs.getTabLabel] is assumed to be a [BorderLayout] container. The chip
 *   goes into its WEST slot (EAST when the close button is on the left).
 * - The group line is painted on the glass pane.
 */
internal class TabStripAdapter(
    val window: EditorWindow,
    private val events: Events,
    private val chipCallbacks: GroupChip.Callbacks,
) : Disposable {
    interface Events {
        fun selectionChanged(strip: TabStripAdapter)
        fun tabsMoved(strip: TabStripAdapter)
        fun tabRemoved(strip: TabStripAdapter, file: VirtualFile)
    }

    private val tabs: JBTabs get() = window.tabbedPane.tabs

    private val chips = HashMap<String, GroupChip>()
    private val badges = HashMap<String, OverflowBadge>()
    private val compacted = IdentityHashMap<Component, Boolean>()
    private var painter: GroupDecorationPainter? = null

    private val animations = IdentityHashMap<JComponent, Shrink>()
    private val timer = Timer(15) { stepAnimations() }
    private var rendered = false

    init {
        tabs.addListener(object : TabsListener {
            override fun selectionChanged(oldSelection: TabInfo?, newSelection: TabInfo?) =
                events.selectionChanged(this@TabStripAdapter)

            override fun tabsMoved() = events.tabsMoved(this@TabStripAdapter)

            override fun tabRemoved(tabToRemove: TabInfo) {
                fileOf(tabToRemove)?.let { events.tabRemoved(this@TabStripAdapter, it) }
            }
        }, this)
    }

    val isAlive: Boolean get() = !window.isDisposed

    fun files(): List<VirtualFile> = tabs.tabs.mapNotNull(::fileOf)

    fun visibleFiles(): List<VirtualFile> = tabs.tabs.filter { !it.isHidden }.mapNotNull(::fileOf)

    fun pinnedFiles(): Set<VirtualFile> = tabs.tabs.filter { it.isPinned }.mapNotNull(::fileOf).toSet()

    // JBTabs has no selection when a hidden tab was opened from elsewhere; the window still knows it.
    fun selectedFile(): VirtualFile? = window.selectedFile

    fun chip(groupId: String): GroupChip? = chips[groupId]?.takeIf { it.isShowing }

    fun reorder(order: List<VirtualFile>) {
        val sorter = tabs as? JBTabsEx ?: run {
            LOG.warn("Editor tabs are not JBTabsEx (${tabs.javaClass.name}), cannot reorder")
            return
        }
        // sortTabs only sorts visible tabs
        val hidden = tabs.tabs.filter { it.isHidden }
        hidden.forEach { it.isHidden = false }
        val position = order.withIndex().associate { it.value to it.index }
        sorter.sortTabs(compareBy { fileOf(it)?.let(position::get) ?: Int.MAX_VALUE })
        hidden.forEach { it.isHidden = true }
    }

    fun render(view: StripView) {
        val all = tabs.tabs
        val selected = selectedFile()
        val selectedInfo = all.firstOrNull { fileOf(it) == selected }
        val animate = rendered && tabs.component.isShowing
        rendered = true

        for (info in all) {
            val hide = info !== selectedInfo && fileOf(info) in view.hidden
            if (animate) animateHidden(info, hide) else if (info.isHidden != hide) info.isHidden = hide
        }
        if (selectedInfo != null && tabs.selectedInfo !== selectedInfo) {
            tabs.select(selectedInfo, false)
        }

        renderChips(view.chips, all, animate)
        renderBadges(view.chips, all)
        renderDecorations(view.chips, all)

        tabs.component.revalidate()
        tabs.component.repaint()
    }

    fun tabAt(screenPoint: Point): Pair<VirtualFile, Boolean>? {
        for (info in tabs.tabs) {
            if (info.isHidden) continue
            val label = tabs.getTabLabel(info) ?: continue
            if (!label.isShowing) continue
            val p = Point(screenPoint)
            SwingUtilities.convertPointFromScreen(p, label)
            if (p.x in 0 until label.width && p.y in 0 until label.height) {
                val file = fileOf(info) ?: return null
                return file to (p.x < label.width / 2)
            }
        }
        return null
    }

    private fun renderChips(views: List<ChipView>, all: List<TabInfo>, animate: Boolean) {
        val wanted = views.associateBy { it.groupId }
        for ((id, chip) in chips.toList()) {
            if (id !in wanted) {
                detach(chip)
                chips.remove(id)
            }
        }
        for (view in views) {
            val info = all.firstOrNull { fileOf(it) == view.carrier } ?: continue
            val label = tabs.getTabLabel(info) as? JComponent ?: continue
            val layout = label.layout as? BorderLayout
            if (layout == null) {
                LOG.warn("Tab label layout is ${label.layout?.javaClass?.name}, expected BorderLayout")
                continue
            }
            val chip = chips.getOrPut(view.groupId) { GroupChip(view.groupId, chipCallbacks) }
            chip.label = view.label
            chip.color = view.color
            chip.collapsed = view.collapsed
            chip.overflow = view.overflow
            if (chip.parent !== label) {
                detach(chip)
                val westFree = layout.getLayoutComponent(BorderLayout.WEST) == null
                val slot = if (westFree) BorderLayout.WEST else BorderLayout.EAST
                if (layout.getLayoutComponent(slot) != null) {
                    LOG.warn("No free slot in tab label for the group chip")
                    continue
                }
                label.add(chip, slot)
            }
            setCompact(label, layout, chip, view.compact, animate)
            label.revalidate()
        }
    }

    private fun setCompact(
        label: JComponent,
        layout: BorderLayout,
        chip: GroupChip,
        compact: Boolean,
        animate: Boolean,
    ) {
        val others = listOf(BorderLayout.CENTER, BorderLayout.EAST, BorderLayout.WEST)
            .mapNotNull(layout::getLayoutComponent)
            .filter { it !== chip }
        val center = layout.getLayoutComponent(BorderLayout.CENTER) as? JComponent
        val isCompact = others.any { compacted.containsKey(it) }
        if (compact && !isCompact) {
            val hide = {
                for (c in others) if (c.isVisible) {
                    c.isVisible = false
                    compacted[c] = true
                }
                label.revalidate()
            }
            if (animate && center != null) shrink(center, label, collapse = true, onDone = hide) else hide()
        } else if (!compact && isCompact) {
            for (c in others) if (compacted.remove(c) != null) c.isVisible = true
            if (animate && center != null) shrink(center, label, collapse = false, onDone = {})
            label.revalidate()
        }
    }

    // Firefox shows "+N" right after the group. It goes into the WEST slot of the next visible tab;
    // when there is none or the slot is taken, the chip shows the count instead.
    private fun renderBadges(views: List<ChipView>, all: List<TabInfo>) {
        val placed = HashSet<String>()
        for (view in views) {
            if (view.overflow <= 0) continue
            val chip = chips[view.groupId] ?: continue
            val lastMember = all.indexOfLast { !it.isHidden && fileOf(it) in view.members }
            val next = all.drop(lastMember + 1).firstOrNull { !it.isHidden } ?: continue
            val label = tabs.getTabLabel(next) as? JComponent ?: continue
            val layout = label.layout as? BorderLayout ?: continue
            val badge = badges.getOrPut(view.groupId) { OverflowBadge(view.groupId) { chipCallbacks.toggle(chip) } }
            badge.count = view.overflow
            badge.color = view.color
            if (badge.parent !== label) {
                detach(badge)
                if (layout.getLayoutComponent(BorderLayout.WEST) != null) continue
                label.add(badge, BorderLayout.WEST)
                label.revalidate()
            }
            chip.overflow = 0
            placed.add(view.groupId)
        }
        for ((id, badge) in badges.toList()) {
            if (id !in placed) {
                detach(badge)
                badges.remove(id)
            }
        }
    }

    private fun renderDecorations(views: List<ChipView>, all: List<TabInfo>) {
        val component = tabs.component
        if (painter == null) {
            if (!component.isShowing) return
            painter = GroupDecorationPainter(component).also {
                IdeGlassPaneUtil.find(component).addPainter(component, it, this)
            }
        }
        painter?.decorations = views.mapNotNull { view ->
            val chip = chips[view.groupId]?.takeIf { it.parent != null } ?: return@mapNotNull null
            val labels = all
                .filter { !it.isHidden && fileOf(it) in view.members && !(view.compact && fileOf(it) == view.carrier) }
                .mapNotNull(tabs::getTabLabel)
            GroupDecoration(chip, labels, badges[view.groupId]?.takeIf { it.parent != null })
        }
    }

    private fun detach(component: JComponent) {
        val parent: Container = component.parent ?: return
        parent.remove(component)
        for (c in parent.components) {
            if (compacted.remove(c) != null) c.isVisible = true
        }
        parent.revalidate()
        parent.repaint()
    }

    // A tab is animated by narrowing the title part of its label (BorderLayout CENTER) to zero
    // before it is hidden, and widening it from zero after it is shown.

    private class Shrink(
        val label: JComponent,
        val full: Int,
        var from: Float,
        var to: Float,
        var start: Long,
        var onDone: () -> Unit,
    ) {
        var fraction = from
    }

    private fun animateHidden(info: TabInfo, hide: Boolean) {
        val label = tabs.getTabLabel(info) as? JComponent
        val center = (label?.layout as? BorderLayout)?.getLayoutComponent(BorderLayout.CENTER) as? JComponent
        val running = center?.let(animations::get)
        if (label == null || center == null || !label.isShowing && !info.isHidden) {
            if (info.isHidden != hide) info.isHidden = hide
            return
        }
        if (hide) {
            if (!info.isHidden && running?.to != 0f) shrink(center, label, collapse = true) { info.isHidden = true }
        } else if (info.isHidden) {
            info.isHidden = false
            shrink(center, label, collapse = false, onDone = {})
        } else if (running?.to == 0f) {
            shrink(center, label, collapse = false, onDone = {})
        }
    }

    private fun shrink(center: JComponent, label: JComponent, collapse: Boolean, onDone: () -> Unit) {
        val running = animations[center]
        val from = running?.fraction ?: if (collapse) 1f else 0f
        val full = running?.full ?: naturalWidth(center)
        animations[center] = Shrink(label, full, from, if (collapse) 0f else 1f, System.currentTimeMillis(), onDone)
        applyWidth(center, full, from)
        if (!timer.isRunning) timer.start()
    }

    private fun naturalWidth(c: JComponent): Int {
        val forced = c.isPreferredSizeSet
        val saved = if (forced) c.preferredSize else null
        c.preferredSize = null
        val width = c.preferredSize.width
        if (forced) c.preferredSize = saved
        return width
    }

    private fun stepAnimations() {
        val now = System.currentTimeMillis()
        for ((center, a) in animations.toList()) {
            val t = ((now - a.start).toFloat() / ANIMATION_MS).coerceIn(0f, 1f)
            val eased = 1f - (1f - t) * (1f - t) * (1f - t)
            a.fraction = a.from + (a.to - a.from) * eased
            if (t >= 1f) {
                animations.remove(center)
                center.preferredSize = null
                a.onDone()
                a.label.revalidate()
            } else {
                applyWidth(center, a.full, a.fraction)
            }
        }
        tabs.component.revalidate()
        tabs.component.repaint()
        if (animations.isEmpty()) timer.stop()
    }

    private fun applyWidth(center: JComponent, full: Int, fraction: Float) {
        center.preferredSize = Dimension((full * fraction).roundToInt(), center.preferredSize.height)
        center.parent?.revalidate()
    }

    override fun dispose() {
        timer.stop()
        for ((center, a) in animations) {
            center.preferredSize = null
            a.onDone()
        }
        animations.clear()
        painter?.decorations = emptyList()
        chips.values.forEach(::detach)
        chips.clear()
        badges.values.forEach(::detach)
        badges.clear()
        if (!isAlive) return
        for (info in tabs.tabs) {
            if (info.isHidden) info.isHidden = false
        }
    }

    companion object {
        fun fileOf(info: TabInfo): VirtualFile? = info.`object` as? VirtualFile
    }
}
