package com.github.harissabil.editortabgroups.ui

import com.github.harissabil.editortabgroups.TabGroupsBundle
import com.github.harissabil.editortabgroups.core.TabGroupColor
import com.github.harissabil.editortabgroups.platform.TabGroupsService
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.event.DocumentEvent

/** Group panel, laid out like Firefox's. In create mode Escape cancels and ungroups, as in Firefox. */
internal object GroupEditorPopup {
    private val deleteForeground = JBColor(0xC94F4F, 0xE88080)

    fun show(project: Project, window: EditorWindow, groupId: String, anchor: JComponent?, createMode: Boolean) {
        val service = TabGroupsService.getInstance(project)
        val group = service.groups(window).firstOrNull { it.id == groupId } ?: return
        var popup: JBPopup? = null
        var cancelCreation = false
        fun close() = popup?.closeOk(null)

        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(10, 12, 10, 12)
        }

        fun add(c: JComponent) = c.also { it.alignmentX = JComponent.LEFT_ALIGNMENT; panel.add(it) }

        if (createMode) {
            add(JBLabel(TabGroupsBundle.message("editor.title.create")).apply {
                font = JBUI.Fonts.label().asBold()
                border = JBUI.Borders.emptyBottom(10)
            })
        }

        val dots = JPanel(java.awt.GridLayout(1, TabGroupColor.entries.size, 0, 0)).apply { isOpaque = false }
        TabGroupColor.entries.forEach { color ->
            dots.add(ColorDot(color, color == group.color) {
                service.setColor(window, groupId, color)
                dots.components.filterIsInstance<ColorDot>().forEach { it.selected = it.color == color; it.repaint() }
            })
        }
        add(dots)

        val nameField = JBTextField(group.label).apply {
            emptyText.text = TabGroupsBundle.message("editor.name.placeholder")
            document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) = service.rename(window, groupId, text.trim())
            })
        }
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(14, 0, 10, 0)
            add(nameField)
        })

        if (createMode) {
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.emptyTop(2)
                add(
                    JButton(TabGroupsBundle.message("editor.done")).apply { addActionListener { close() } },
                    BorderLayout.EAST
                )
            })
        } else {
            val paths = group.files.mapNotNull { VirtualFileManager.getInstance().findFileByUrl(it)?.path }
            if (paths.isNotEmpty()) {
                add(MenuRow(TabGroupsBundle.message("editor.action.copy.paths", paths.size)) {
                    close()
                    CopyPasteManager.getInstance()
                        .setContents(StringSelection(paths.joinToString("\n") { FileUtil.toSystemDependentName(it) }))
                })
            }
            add(MenuRow(TabGroupsBundle.message("editor.action.save")) {
                close(); service.saveAndCloseGroup(
                window,
                groupId
            )
            })
            add(MenuRow(TabGroupsBundle.message("editor.action.ungroup")) { close(); service.ungroup(window, groupId) })
            add(
                MenuRow(
                    TabGroupsBundle.message("editor.action.delete"),
                    deleteForeground
                ) { close(); service.deleteGroup(window, groupId) })
        }

        panel.registerKeyboardAction(
            { close() },
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
            JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT
        )
        panel.registerKeyboardAction({
            cancelCreation = createMode
            popup?.cancel()
        }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)

        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, nameField)
            .setRequestFocus(true)
            .setFocusable(true)
            .setCancelOnClickOutside(true)
            .setCancelKeyEnabled(false)
            .setMovable(false)
            .setResizable(false)
            .setCancelCallback {
                if (cancelCreation) service.ungroup(window, groupId)
                true
            }
            .createPopup()
        if (anchor != null && anchor.isShowing) popup.showUnderneathOf(anchor) else popup.showCenteredInCurrentWindow(
            project
        )
    }

    private class MenuRow(text: String, foreground: java.awt.Color? = null, private val onClick: () -> Unit) :
        JBLabel(text) {
        private var hovered = false

        init {
            border = JBUI.Borders.empty(6, 7)
            if (foreground != null) this.foreground = foreground
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    hovered = true; repaint()
                }

                override fun mouseExited(e: MouseEvent) {
                    hovered = false; repaint()
                }

                override fun mouseReleased(e: MouseEvent) {
                    if (contains(e.point)) onClick()
                }
            })
        }

        override fun paintComponent(g: Graphics) {
            if (hovered) {
                val g2 = g.create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = UIUtil.getListSelectionBackground(false)
                    g2.fillRoundRect(0, 0, width, height, JBUI.scale(6), JBUI.scale(6))
                } finally {
                    g2.dispose()
                }
            }
            super.paintComponent(g)
        }
    }

    private class ColorDot(val color: TabGroupColor, var selected: Boolean, private val onPick: () -> Unit) :
        JComponent() {
        init {
            toolTipText = GroupColors.displayName(color)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseReleased(e: MouseEvent) {
                    if (contains(e.point)) onPick()
                }
            })
        }

        override fun getPreferredSize() = Dimension(JBUI.scale(30), JBUI.scale(32))

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val c = GroupColors.chip(color)
                val dot = JBUI.scale(16)
                val x = (width - dot) / 2
                val y = (height - dot) / 2
                g2.color = c
                g2.fillOval(x, y, dot, dot)
                if (selected) {
                    val stroke = JBUI.scale(2).toFloat()
                    val ring = dot + JBUI.scale(8)
                    g2.stroke = BasicStroke(stroke)
                    g2.drawOval((width - ring) / 2, (height - ring) / 2, ring, ring)
                }
            } finally {
                g2.dispose()
            }
        }
    }
}
