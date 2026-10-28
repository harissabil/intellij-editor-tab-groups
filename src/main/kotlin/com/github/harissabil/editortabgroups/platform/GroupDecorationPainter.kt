package com.github.harissabil.editortabgroups.platform

import com.github.harissabil.editortabgroups.ui.GroupChip
import com.github.harissabil.editortabgroups.ui.GroupColors
import com.github.harissabil.editortabgroups.ui.OverflowBadge
import com.intellij.openapi.ui.AbstractPainter
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Component
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import javax.swing.JComponent
import javax.swing.SwingUtilities

internal class GroupDecoration(val chip: GroupChip, val memberLabels: List<Component>, val badge: OverflowBadge? = null)

/**
 * Paints on the glass pane above the tab strip: the group line under member tabs, and the chip and
 * "+N" badge on the tab bar background. The chip and badge live inside tab labels, and without the
 * mask the label's own hover and selection background would show behind them.
 */
internal class GroupDecorationPainter(private val tabsComponent: JComponent) : AbstractPainter() {
    var decorations: List<GroupDecoration> = emptyList()
        set(value) {
            field = value
            setNeedsRepaint(true)
        }

    override fun needsRepaint(): Boolean = decorations.isNotEmpty()

    override fun executePaint(component: Component, g: Graphics2D) {
        if (!tabsComponent.isShowing) return
        val clip = SwingUtilities.convertRectangle(tabsComponent, tabsComponent.visibleRect, component)
        val g2 = g.create() as Graphics2D
        try {
            g2.clip(clip)
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            for (d in decorations) paint(component, g2, d)
        } finally {
            g2.dispose()
        }
    }

    private fun paint(target: Component, g: Graphics2D, d: GroupDecoration) {
        val chip = d.chip
        if (!chip.isShowing) return
        mask(target, g, chip)
        val pill = SwingUtilities.convertRectangle(chip, chip.pillBounds(), target)
        chip.paintPill(g, pill)

        var badgeRight: Int? = null
        d.badge?.takeIf { it.isShowing }?.let { badge ->
            mask(target, g, badge)
            val r = SwingUtilities.convertRectangle(badge, badge.badgeBounds(), target)
            badge.paintBadge(g, r)
            badgeRight = r.x + r.width
        }

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
        badgeRight?.let { right = maxOf(right, it) }
        val thickness = JBUI.scale(2)
        g.color = GroupColors.chip(chip.color)
        val y = bottom - thickness - JBUI.scale(1)
        g.fillRoundRect(pill.x, y, right - pill.x - JBUI.scale(4), thickness, thickness, thickness)
    }

    private fun mask(target: Component, g: Graphics2D, c: JComponent) {
        val host = c.parent ?: return
        val area = SwingUtilities.convertRectangle(host, Rectangle(c.x, 0, c.width, host.height), target)
        g.color = stripBackground()
        g.fillRect(area.x, area.y, area.width, area.height)
    }

    private fun stripBackground(): Color {
        var c: Component? = tabsComponent
        while (c != null) {
            if (c.isOpaque && c.background != null) return c.background
            c = c.parent
        }
        return tabsComponent.background
    }
}
