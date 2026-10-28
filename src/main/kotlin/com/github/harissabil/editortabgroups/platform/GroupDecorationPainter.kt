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

/**
 * Paints on the glass pane: the group line under member tabs, and the chip pill again on top, because
 * the tab label paints its right-edge fade-out over its children, which covers the chip of a collapsed group.
 */
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
        if (!chip.isShowing) return
        val pill = SwingUtilities.convertRectangle(chip, chip.pillBounds(), target)
        chip.paintPill(g, pill)
        // Like Firefox: no line under a collapsed group unless its selected tab is showing.
        if (d.memberLabels.none { it.isShowing }) return
        var right = pill.x + pill.width
        var bottom = Int.MIN_VALUE
        for (member in d.memberLabels) {
            if (!member.isShowing) continue
            val r = SwingUtilities.convertRectangle(member.parent, member.bounds, target)
            right = maxOf(right, r.x + r.width)
            bottom = maxOf(bottom, r.y + r.height)
        }
        d.badge?.takeIf { it.isShowing }?.let { badge ->
            val r = SwingUtilities.convertRectangle(badge, badge.badgeBounds(), target)
            right = maxOf(right, r.x + r.width)
        }
        val thickness = JBUI.scale(2)
        val y = bottom - thickness - JBUI.scale(1)
        g.color = GroupColors.chip(chip.color)
        g.fillRoundRect(pill.x, y, right - pill.x - JBUI.scale(4), thickness, thickness, thickness)
    }
}
