package com.github.harissabil.editortabgroups.ui

import com.github.harissabil.editortabgroups.TabGroupsBundle
import com.github.harissabil.editortabgroups.core.TabGroupColor
import com.intellij.ide.ui.UISettings
import com.intellij.util.ui.JBUI
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.SwingUtilities

/** "+N" after the selected tab of a collapsed group. Clicking it expands the group. */
internal class OverflowBadge(val groupId: String, private val onClick: () -> Unit) : JComponent() {
    var count: Int = 0
        set(value) {
            if (field != value) {
                field = value
                toolTipText = TabGroupsBundle.message("overflow.tooltip", value)
                revalidate()
                repaint()
            }
        }
    var color: TabGroupColor = TabGroupColor.BLUE
        set(value) {
            if (field != value) {
                field = value; repaint()
            }
        }

    init {
        isOpaque = false
        font = JBUI.Fonts.label().asBold()
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = e.consume()

            override fun mouseReleased(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e) && contains(e.point)) onClick()
                e.consume()
            }
        })
    }

    private fun text() = "+$count"

    override fun getPreferredSize(): Dimension {
        val fm = getFontMetrics(font)
        return Dimension(fm.stringWidth(text()) + JBUI.scale(14), fm.height + JBUI.scale(6))
    }

    fun badgeBounds(): Rectangle = Rectangle(0, 0, width - JBUI.scale(6), height)

    fun paintBadge(g: Graphics2D, r: Rectangle) {
        val g2 = g.create() as Graphics2D
        try {
            UISettings.setupAntialiasing(g2)
            val fm = g2.getFontMetrics(font)
            g2.font = font
            g2.color = GroupColors.chip(color)
            g2.drawString(
                text(),
                r.x + (r.width - fm.stringWidth(text())) / 2,
                r.y + (r.height - fm.height) / 2 + fm.ascent
            )
        } finally {
            g2.dispose()
        }
    }

    override fun paintComponent(g: Graphics) = paintBadge(g as Graphics2D, badgeBounds())
}
