package com.github.harissabil.editortabgroups.ui

import com.github.harissabil.editortabgroups.TabGroupsBundle
import com.github.harissabil.editortabgroups.core.TabGroupColor
import com.intellij.ide.ui.UISettings
import com.intellij.util.ui.JBUI
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.SwingUtilities
import kotlin.math.abs

/** Group label in the tab strip. Click toggles collapse, right click opens the group panel, drag moves the group. */
internal class GroupChip(val groupId: String, private val callbacks: Callbacks) : JComponent() {
    interface Callbacks {
        fun toggle(chip: GroupChip)
        fun showMenu(chip: GroupChip)
        fun dragStarted(chip: GroupChip)

        fun dragFinished(chip: GroupChip, screenPoint: Point)
    }

    var label: String = ""
        set(value) {
            if (field != value) {
                field = value; revalidate(); repaint()
            }
        }
    var color: TabGroupColor = TabGroupColor.BLUE
        set(value) {
            if (field != value) {
                field = value; repaint()
            }
        }
    var collapsed: Boolean = false
        set(value) {
            if (field != value) {
                field = value; updateTooltip()
            }
        }

    var overflow: Int = 0
        set(value) {
            if (field != value) {
                field = value; revalidate(); repaint()
            }
        }

    private var pressPoint: Point? = null
    private var dragging = false

    init {
        isOpaque = false
        font = JBUI.Fonts.label().asBold()
        updateTooltip()
        val mouse = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) {
                    callbacks.showMenu(this@GroupChip)
                } else if (SwingUtilities.isLeftMouseButton(e)) {
                    pressPoint = e.point
                }
                e.consume()
            }

            override fun mouseDragged(e: MouseEvent) {
                val start = pressPoint ?: return
                if (!dragging && (abs(e.x - start.x) > JBUI.scale(6) || abs(e.y - start.y) > JBUI.scale(6))) {
                    dragging = true
                    cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                    callbacks.dragStarted(this@GroupChip)
                }
                e.consume()
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) {
                    callbacks.showMenu(this@GroupChip)
                } else if (dragging) {
                    callbacks.dragFinished(this@GroupChip, e.locationOnScreen)
                } else if (pressPoint != null && SwingUtilities.isLeftMouseButton(e) && contains(e.point)) {
                    callbacks.toggle(this@GroupChip)
                }
                pressPoint = null
                dragging = false
                cursor = Cursor.getDefaultCursor()
                e.consume()
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
    }

    private fun updateTooltip() {
        val name = label.ifEmpty { TabGroupsBundle.message("group.name.default") }
        toolTipText =
            TabGroupsBundle.message(if (collapsed) "chip.tooltip.collapsed" else "chip.tooltip.expanded", name)
    }

    private fun text(): String = when {
        overflow > 0 && label.isEmpty() -> "+$overflow"
        overflow > 0 -> "$label  +$overflow"
        else -> label
    }

    private fun pillHeight(): Int = getFontMetrics(font).height + JBUI.scale(6)

    private fun gap(): Int = JBUI.scale(6)

    override fun getPreferredSize(): Dimension {
        val fm = getFontMetrics(font)
        val h = pillHeight()
        val textWidth = fm.stringWidth(text())
        val w = if (textWidth == 0) h else minOf(textWidth + JBUI.scale(16), JBUI.scale(170))
        return Dimension(w + gap(), h)
    }

    fun pillBounds(): Rectangle {
        val h = minOf(pillHeight(), height)
        return Rectangle(0, (height - h) / 2, width - gap(), h)
    }

    fun paintPill(g: Graphics2D, r: Rectangle) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            UISettings.setupAntialiasing(g2)
            val arc = JBUI.scale(10)
            g2.color = GroupColors.chip(color)
            g2.fillRoundRect(r.x, r.y, r.width, r.height, arc, arc)
            val text = text()
            if (text.isNotEmpty()) {
                val fm = g2.getFontMetrics(font)
                val clipped = fit(text, fm, r.width - JBUI.scale(12))
                g2.color = GroupColors.chipText(color)
                g2.font = font
                g2.drawString(
                    clipped,
                    r.x + (r.width - fm.stringWidth(clipped)) / 2,
                    r.y + (r.height - fm.height) / 2 + fm.ascent
                )
            }
        } finally {
            g2.dispose()
        }
    }

    override fun paintComponent(g: Graphics) = paintPill(g as Graphics2D, pillBounds())

    private fun fit(text: String, fm: java.awt.FontMetrics, maxWidth: Int): String {
        if (fm.stringWidth(text) <= maxWidth) return text
        var end = text.length
        while (end > 0 && fm.stringWidth(text.substring(0, end) + "…") > maxWidth) end--
        return text.substring(0, end) + "…"
    }

    override fun getToolTipText(event: MouseEvent?): String? {
        updateTooltip()
        return super.getToolTipText(event)
    }
}
