package com.github.harissabil.editortabgroups.ui

import com.github.harissabil.editortabgroups.TabGroupsBundle
import com.github.harissabil.editortabgroups.core.TabGroup
import com.github.harissabil.editortabgroups.platform.TabGroupsService
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

internal data class TabContext(val project: Project, val window: EditorWindow, val file: VirtualFile?) {
    val service: TabGroupsService get() = TabGroupsService.getInstance(project)

    companion object {
        /** From the tab popup, or the current window and its selected file elsewhere. */
        fun of(e: AnActionEvent): TabContext? {
            val project = e.project ?: return null
            val window = e.getData(EditorWindow.DATA_KEY)
                ?: FileEditorManagerEx.getInstanceEx(project).currentWindow
                ?: return null
            val file = e.getData(CommonDataKeys.VIRTUAL_FILE)?.takeIf { it in window.fileList } ?: window.selectedFile
            return TabContext(project, window, file)
        }
    }
}

internal fun groupName(group: TabGroup): String = group.label.ifEmpty { TabGroupsBundle.message("group.name.default") }

internal fun openEditorAfterCreate(ctx: TabContext, groupId: String) {
    ApplicationManager.getApplication().invokeLater({
        GroupEditorPopup.show(
            ctx.project,
            ctx.window,
            groupId,
            ctx.service.chip(ctx.window, groupId),
            createMode = true
        )
    }, ModalityState.nonModal(), ctx.project.disposed)
}

private fun isPinned(ctx: TabContext): Boolean = ctx.file?.let { ctx.window.isFilePinned(it) } ?: false

class AddTabToNewGroupAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val ctx = TabContext.of(e)
        e.presentation.isEnabledAndVisible =
            ctx?.file != null && !isPinned(ctx) && ctx.service.groups(ctx.window).isEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val ctx = TabContext.of(e) ?: return
        val file = ctx.file ?: return
        val id = ctx.service.createGroup(ctx.window, listOf(file)) ?: return
        openEditorAfterCreate(ctx, id)
    }
}

/** "Add Tab to Group" submenu: New Group, then the window's other groups. */
class AddTabToGroupMenu : ActionGroup(), com.intellij.openapi.project.DumbAware {
    init {
        isPopup = true
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val ctx = TabContext.of(e)
        e.presentation.isEnabledAndVisible =
            ctx?.file != null && !isPinned(ctx) && ctx.service.groups(ctx.window).isNotEmpty()
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val ctx = e?.let(TabContext::of) ?: return emptyArray()
        val file = ctx.file ?: return emptyArray()
        val current = ctx.service.groupOf(ctx.window, file)?.id
        val others = ctx.service.groups(ctx.window).filter { it.id != current }
        val newGroup = DumbAwareAction.create(TabGroupsBundle.message("action.new.group")) {
            ctx.service.createGroup(ctx.window, listOf(file))?.let { openEditorAfterCreate(ctx, it) }
        }
        val joins = others.map { group ->
            object : DumbAwareAction(groupName(group), null, GroupColors.icon(group.color)) {
                override fun actionPerformed(e: AnActionEvent) =
                    ctx.service.addToGroup(ctx.window, group.id, listOf(file))
            }
        }
        return if (joins.isEmpty()) arrayOf(newGroup) else arrayOf(
            newGroup,
            Separator.getInstance(),
            *joins.toTypedArray()
        )
    }
}

class RemoveTabFromGroupAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val ctx = TabContext.of(e)
        e.presentation.isEnabledAndVisible = ctx?.file?.let { ctx.service.groupOf(ctx.window, it) } != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val ctx = TabContext.of(e) ?: return
        ctx.service.removeFromGroup(ctx.window, listOfNotNull(ctx.file))
    }
}

class ToggleTabGroupAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val ctx = TabContext.of(e)
        e.presentation.isEnabledAndVisible = ctx?.file?.let { ctx.service.groupOf(ctx.window, it) } != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val ctx = TabContext.of(e) ?: return
        val group = ctx.file?.let { ctx.service.groupOf(ctx.window, it) } ?: return
        ctx.service.toggleCollapsed(ctx.window, group.id)
    }
}

class ManageTabGroupAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val ctx = TabContext.of(e)
        e.presentation.isEnabledAndVisible = ctx?.file?.let { ctx.service.groupOf(ctx.window, it) } != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val ctx = TabContext.of(e) ?: return
        val group = ctx.file?.let { ctx.service.groupOf(ctx.window, it) } ?: return
        GroupEditorPopup.show(
            ctx.project,
            ctx.window,
            group.id,
            ctx.service.chip(ctx.window, group.id),
            createMode = false
        )
    }
}

class ReopenSavedGroupMenu : ActionGroup(), com.intellij.openapi.project.DumbAware {
    init {
        isPopup = true
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val ctx = TabContext.of(e)
        e.presentation.isEnabledAndVisible = ctx != null && ctx.service.savedGroups().isNotEmpty()
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val ctx = e?.let(TabContext::of) ?: return emptyArray()
        val saved = ctx.service.savedGroups()
        val reopen = saved.map { s ->
            object : DumbAwareAction(
                TabGroupsBundle.message("action.saved.group", groupName(s.group), s.group.files.size),
                null,
                GroupColors.icon(s.group.color),
            ) {
                override fun actionPerformed(e: AnActionEvent) = ctx.service.reopenSaved(ctx.window, s.group.id)
            }
        }
        val forget = object : ActionGroup(TabGroupsBundle.message("action.forget.saved"), true),
            com.intellij.openapi.project.DumbAware {
            override fun getChildren(e: AnActionEvent?): Array<AnAction> = saved.map { s ->
                object : DumbAwareAction(groupName(s.group), null, GroupColors.icon(s.group.color)) {
                    override fun actionPerformed(e: AnActionEvent) = ctx.service.forgetSaved(s.group.id)
                }
            }.toTypedArray()
        }
        return arrayOf(*reopen.toTypedArray(), Separator.getInstance(), forget)
    }
}
