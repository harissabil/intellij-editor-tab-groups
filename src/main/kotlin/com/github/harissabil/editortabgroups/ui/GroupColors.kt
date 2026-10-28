package com.github.harissabil.editortabgroups.ui

import com.github.harissabil.editortabgroups.TabGroupsBundle
import com.github.harissabil.editortabgroups.core.TabGroupColor
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

internal object GroupColors {
    fun chip(color: TabGroupColor): JBColor = when (color) {
        TabGroupColor.BLUE -> JBColor(0x3574F0, 0x80A6F2)
        TabGroupColor.PURPLE -> JBColor(0x8A4FD8, 0xB596EE)
        TabGroupColor.CYAN -> JBColor(0x0E8E9E, 0x6CC8D3)
        TabGroupColor.ORANGE -> JBColor(0xD9730D, 0xE38B62)
        TabGroupColor.YELLOW -> JBColor(0xC29200, 0xE8C66A)
        TabGroupColor.PINK -> JBColor(0xD6407F, 0xDE7E9C)
        TabGroupColor.GREEN -> JBColor(0x208A3C, 0x86C892)
        TabGroupColor.GRAY -> JBColor(0x6C707E, 0xA3A6AD)
        TabGroupColor.RED -> JBColor(0xD12C2C, 0xEE7F7F)
    }

    fun chipText(color: TabGroupColor): Color =
        if (color == TabGroupColor.YELLOW) JBColor(0x1E1F22, 0x1E1F22) else JBColor(0xFFFFFF, 0x1E1F22)

    fun displayName(color: TabGroupColor): String = TabGroupsBundle.message("color.${color.name.lowercase()}")

    fun icon(color: TabGroupColor, size: Int = 12): Icon = SwatchIcon(color, size)

    private class SwatchIcon(private val color: TabGroupColor, private val size: Int) : Icon {
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = chip(color)
                val s = JBUI.scale(size)
                g2.fillOval(x, y, s, s)
            } finally {
                g2.dispose()
            }
        }

        override fun getIconWidth(): Int = JBUI.scale(size)
        override fun getIconHeight(): Int = JBUI.scale(size)
    }
}
