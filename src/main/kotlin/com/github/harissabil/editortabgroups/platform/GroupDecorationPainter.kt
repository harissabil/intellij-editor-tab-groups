package com.github.harissabil.editortabgroups.platform

import com.github.harissabil.editortabgroups.core.TabGroupColor
import com.github.harissabil.editortabgroups.ui.GroupChip
import com.github.harissabil.editortabgroups.ui.GroupColors
import com.github.harissabil.editortabgroups.ui.OverflowBadge
import com.intellij.openapi.ui.AbstractPainter
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import javax.swing.JComponent
import javax.swing.SwingUtilities

internal class GroupDecoration(val chip: GroupChip, val memberLabels: List<Component>, val badge: OverflowBadge? = null)

/** Paints the group line under member tabs, and the drop marker while a group is dragged, on the glass pane. */
internal class GroupDecorationPainter(private val tabsComponent: JComponent) : AbstractPainter() {
    var decorations: List<GroupDecoration> = emptyList()
        set(value) {
            field = value
            setNeedsRepaint(true)
        }

    /** Insertion bar while a group is dragged, in tab strip coordinates. */
    var dropMarker: Pair<Rectangle, TabGroupColor>? = null
        set(value) {
            field = value
            setNeedsRepaint(true)
        }

    override fun needsRepaint(): Boolean = decorations.isNotEmpty() || dropMarker != null

    override fun executePaint(component: Component, g: Graphics2D) {
        if (!tabsComponent.isShowing) return
        val clip = SwingUtilities.convertRectangle(tabsComponent, tabsComponent.visibleRect, component)
        val g2 = g.create() as Graphics2D
        try {
            g2.clip(clip)
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            for (d in decorations) paint(component, g2, d)
            dropMarker?.let { (rect, color) ->
                val r = SwingUtilities.convertRectangle(tabsComponent, rect, component)
                g2.color = GroupColors.chip(color)
                g2.fillRoundRect(r.x, r.y, r.width, r.height, r.width, r.width)
            }
        } finally {
            g2.dispose()
        }
    }

    private fun paint(target: Component, g: Graphics2D, d: GroupDecoration) {
        val chip = d.chip
        // Like Firefox: no line under a collapsed group unless its selected tab is showing.
        val members = d.memberLabels.mapNotNull { (it as? JComponent)?.let { label -> visibleArea(label, target) } }
        if (members.isEmpty()) return
        val start = visibleArea(chip, target)?.let { SwingUtilities.convertRectangle(chip, chip.pillBounds(), target).x }
        var left = start ?: Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        for (r in members) {
            // starts at the label when it is visible, otherwise at the first visible member
            if (start == null) left = minOf(left, r.x)
            right = maxOf(right, r.x + r.width)
            bottom = maxOf(bottom, r.y + r.height)
        }
        d.badge?.let { visibleArea(it, target) }?.let { right = maxOf(right, it.x + it.width) }
        val thickness = JBUI.scale(2)
        val y = bottom - thickness - JBUI.scale(1)
        g.color = GroupColors.chip(chip.color)
        g.fillRoundRect(left, y, right - left - JBUI.scale(4), thickness, thickness, thickness)
    }

    // What Swing would actually show of [c]: tabs that overflow the strip are laid out with zero size, and
    // tabs scrolled to an edge are clipped, so the component's own bounds can't be trusted.
    private fun visibleArea(c: JComponent, target: Component): Rectangle? {
        if (!c.isShowing) return null
        val visible = c.visibleRect
        if (visible.isEmpty) return null
        return SwingUtilities.convertRectangle(c, visible, target)
    }
}
