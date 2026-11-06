// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.actions.compareWithRef

import com.intellij.icons.AllIcons
import com.intellij.ide.ActivityTracker
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.KeepPopupOnPerform
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.actionSystem.impl.ActionButtonWithText
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogBuilder
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsBundle
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.VcsNotifier
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangesUtil
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserBase
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import com.intellij.openapi.vcs.changes.ui.SimpleAsyncChangesBrowser
import com.intellij.openapi.vcs.changes.ui.browser.LoadingChangesPanel
import com.intellij.openapi.vcs.history.actions.GetVersionAction
import com.intellij.openapi.vcs.history.actions.GetVersionAction.FileRevisionProvider
import com.intellij.openapi.vcs.impl.AbstractVcsHelperImpl
import com.intellij.openapi.vcs.impl.ChangesBrowserToolWindow
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.content.ContentFactory
import com.intellij.util.concurrency.annotations.RequiresEdt
import git4idea.GitReference
import git4idea.actions.GitCompareWithRefAction
import git4idea.repo.GitRepository
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Displays directory changes between a Git reference and the working tree.
 */
internal class GitCompareWithRefChangesPanel private constructor(
  val repository: GitRepository,
  val file: VirtualFile,
  val refToCompare: GitReference,
) : JPanel(BorderLayout()), UiDataProvider, Disposable {
  val project: Project = repository.project

  private val changesBrowser = MyChangesBrowser(project)
  private val loadingPanel = LoadingChangesPanel(changesBrowser, this)

  private var allLoadedChanges: List<Change> = emptyList()
  private var currentDisplayedChanges: List<Change> = emptyList()
  private var authorState: AuthorState = AuthorState.Loading
  private val selectedAuthorKeys: MutableSet<String> = LinkedHashSet()

  private var activeAuthorIndicator: ProgressIndicator? = null
  private var loadGeneration: Int = 0
  private var isPanelDisposed: Boolean = false

  init {
    Disposer.register(this, changesBrowser)
    add(loadingPanel, BorderLayout.CENTER)
  }

  override fun dispose() {
    isPanelDisposed = true
    cancelAuthorLoading()
  }

  fun getChangesBrowser(): ChangesBrowserBase = changesBrowser

  @RequiresEdt
  fun reloadChanges() {
    loadGeneration++
    val generation = loadGeneration
    cancelAuthorLoading()
    authorState = AuthorState.Loading
    ActivityTracker.getInstance().inc()

    loadingPanel.loadChangesInBackground(
      { GitCompareWithRefAction.getDiffChanges(repository, file, refToCompare) },
      { changes -> applyLoadedChanges(changes, generation) },
    )
  }

  @RequiresEdt
  private fun applyLoadedChanges(changes: Collection<Change>?, generation: Int) {
    if (isPanelDisposed || generation != loadGeneration) {
      return
    }
    val changeList = changes?.toList() ?: emptyList()
    allLoadedChanges = changeList
    currentDisplayedChanges = changeList
    changesBrowser.setChangesToDisplay(changeList)

    if (changeList.isEmpty()) {
      selectedAuthorKeys.clear()
      val emptyIndex = GitCompareWithRefAuthorIndex.build(
        rootPath = repository.root.path,
        diffChanges = emptyList(),
        commits = emptyList(),
        uncommittedChanges = emptyList(),
        currentUser = null,
      )
      authorState = AuthorState.Ready(emptyIndex)
      ActivityTracker.getInstance().inc()
      return
    }

    val indicator = EmptyProgressIndicator()
    activeAuthorIndicator = indicator
    ApplicationManager.getApplication().executeOnPooledThread {
      try {
        val index = ProgressManager.getInstance().runProcess(
          Computable {
            GitCompareWithRefAuthorIndex.loadForCompare(project, repository, file, refToCompare, changeList)
          },
          indicator,
        )
        ApplicationManager.getApplication().invokeLater(
          {
            if (!isPanelDisposed && generation == loadGeneration && !indicator.isCanceled) {
              onAuthorIndexLoaded(index)
            }
          },
          ModalityState.any(),
        )
      }
      catch (_: ProcessCanceledException) {
        // Canceled on reload or panel disposal.
      }
      catch (t: Throwable) {
        ApplicationManager.getApplication().invokeLater(
          {
            if (!isPanelDisposed && generation == loadGeneration && !indicator.isCanceled) {
              onAuthorIndexFailed(t)
            }
          },
          ModalityState.any(),
        )
      }
    }
  }

  @RequiresEdt
  private fun onAuthorIndexLoaded(index: GitCompareWithRefAuthorIndex) {
    val availableKeys = index.authors.mapTo(HashSet()) { it.key }
    selectedAuthorKeys.retainAll(availableKeys)
    authorState = AuthorState.Ready(index)
    applyAuthorFilter()
    ActivityTracker.getInstance().inc()
  }

  @RequiresEdt
  private fun onAuthorIndexFailed(error: Throwable) {
    selectedAuthorKeys.clear()
    val reason = error.localizedMessage?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
    authorState = AuthorState.Failed(reason)
    currentDisplayedChanges = allLoadedChanges
    changesBrowser.setChangesToDisplay(allLoadedChanges)
    ActivityTracker.getInstance().inc()
  }

  @RequiresEdt
  private fun applyAuthorFilter() {
    val state = authorState
    val filtered = if (state is AuthorState.Ready) {
      state.index.filterChanges(selectedAuthorKeys)
    }
    else {
      allLoadedChanges
    }
    currentDisplayedChanges = filtered
    changesBrowser.setChangesToDisplay(filtered)
    ActivityTracker.getInstance().inc()
  }

  private fun cancelAuthorLoading() {
    activeAuthorIndicator?.cancel()
    activeAuthorIndicator = null
  }

  override fun uiDataSnapshot(sink: DataSink) {
    sink[DATA_KEY] = this
  }

  private sealed interface AuthorState {
    data object Loading : AuthorState
    data class Ready(val index: GitCompareWithRefAuthorIndex) : AuthorState
    data class Failed(val reason: String) : AuthorState
  }

  private class MyChangesBrowser(project: Project) : SimpleAsyncChangesBrowser(project, false, true), Disposable {
    init {
      hideViewerBorder()
      myViewer.setTreeStateStrategy(ChangesTree.KEEP_NON_EMPTY)
    }

    override fun dispose() {
      shutdown()
    }

    override fun createToolbarActions(): List<AnAction> {
      val actions = ArrayList<AnAction>()
      actions.add(MyRefreshAction())
      actions.addAll(super.createToolbarActions())
      actions.add(MyGetVersionAction())
      actions.add(MyCopyPathsAction())
      actions.add(Separator.getInstance())
      actions.add(MyUserFilterAction())
      return actions
    }

    override fun createPopupMenuActions(): List<AnAction> {
      val actions = ArrayList<AnAction>(super.createPopupMenuActions())
      ActionManager.getInstance().getAction("ChangesView.CreatePatchFromChanges")?.let { actions.add(it) }
      actions.add(MyGetVersionAction())
      actions.add(MyCopyPathsAction())
      return actions
    }
  }

  private class MyRefreshAction : DumbAwareAction(
    VcsBundle.messagePointer("action.name.refresh.compare.with.local.panel"),
    VcsBundle.messagePointer("action.description.refresh.compare.with.local.panel"),
    AllIcons.Actions.Refresh,
  ) {
    init {
      ActionManager.getInstance().getAction(IdeActions.ACTION_REFRESH)?.let { copyShortcutFrom(it) }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
      val panel = e.getData(DATA_KEY)
      e.presentation.isEnabledAndVisible = panel != null
    }

    override fun actionPerformed(e: AnActionEvent) {
      val panel = e.getData(DATA_KEY) ?: return
      FileDocumentManager.getInstance().saveAllDocuments()
      panel.reloadChanges()
    }
  }

  private class MyGetVersionAction : DumbAwareAction() {
    init {
      ActionUtil.copyFrom(this, "Vcs.GetVersion")
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
      val project = e.project
      val panel = e.getData(DATA_KEY)
      if (project == null || panel == null) {
        e.presentation.isEnabledAndVisible = false
        return
      }
      e.presentation.isVisible = true
      e.presentation.isEnabled = panel.changesBrowser.selectedChanges.isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
      val project = e.project ?: return
      val panel = e.getData(DATA_KEY) ?: return
      val selectedChanges = panel.changesBrowser.selectedChanges
      if (selectedChanges.isEmpty()) return

      val providers = selectedChanges.map { change -> MyFileContentProvider(change) }
      GetVersionAction.doGet(project, VcsBundle.message("activity.name.get"), providers) {
        FileDocumentManager.getInstance().saveAllDocuments()
        panel.reloadChanges()
      }
    }
  }

  private class MyFileContentProvider(private val change: Change) : FileRevisionProvider {
    override fun getFilePath(): FilePath = ChangesUtil.getFilePath(change)

    @Throws(VcsException::class)
    override fun getContent(): GetVersionAction.FileRevisionContent? {
      val revision = change.beforeRevision ?: return null
      val bytes = ChangesUtil.loadContentRevision(revision)
      val oldFilePath = if (change.isMoved || change.isRenamed) revision.file else null
      return GetVersionAction.FileRevisionContent(bytes, oldFilePath)
    }
  }

  private class MyCopyPathsAction : DumbAwareAction(
    GitCompareWithRefBundle.messagePointer("copy.paths.toolbar.text"),
    GitCompareWithRefBundle.messagePointer("copy.paths.description"),
    AllIcons.Actions.Copy,
  ) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
      val panel = e.getData(DATA_KEY)
      if (panel == null) {
        e.presentation.isEnabledAndVisible = false
        return
      }
      e.presentation.isVisible = true
      e.presentation.isEnabled = panel.currentDisplayedChanges.isNotEmpty()
      e.presentation.icon = AllIcons.Actions.Copy
      e.presentation.description = GitCompareWithRefBundle.message("copy.paths.description")
      e.presentation.text = if (e.isFromContextMenu) {
        GitCompareWithRefBundle.message("copy.paths.menu.text")
      }
      else {
        GitCompareWithRefBundle.message("copy.paths.toolbar.text")
      }
    }

    override fun actionPerformed(e: AnActionEvent) {
      val panel = e.getData(DATA_KEY) ?: return
      val changes = panel.currentDisplayedChanges
      if (changes.isEmpty()) return

      val exported = GitCompareWithRefExporter.exportChangesByType(panel.repository.root, changes)
      CopyPasteManager.getInstance().setContents(StringSelection(exported))
      VcsNotifier.getInstance(panel.project).notifyInfo(
        null,
        "",
        GitCompareWithRefBundle.message("copy.paths.notification", changes.size, panel.refToCompare.name),
      )
    }
  }

  private class MyUserFilterAction : DumbAwareAction(), CustomComponentAction {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
      return object : ActionButtonWithText(this, presentation, place, ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE) {
        init {
          presentation.addPropertyChangeListener { evt ->
            if (evt.propertyName == Presentation.PROP_TEXT || evt.propertyName == Presentation.PROP_DESCRIPTION) {
              updateToolTipText()
              revalidate()
              repaint()
            }
          }
        }

        override fun shallPaintDownArrow(): Boolean = true
      }
    }

    override fun update(e: AnActionEvent) {
      val panel = e.getData(DATA_KEY)
      if (panel == null) {
        e.presentation.isEnabledAndVisible = false
        return
      }
      e.presentation.isVisible = true
      e.presentation.isPopupGroup = true

      when (val state = panel.authorState) {
        is AuthorState.Loading -> {
          e.presentation.isEnabled = false
          e.presentation.text = GitCompareWithRefBundle.message("user.filter.text")
          e.presentation.description = GitCompareWithRefBundle.message("user.filter.loading")
        }
        is AuthorState.Failed -> {
          e.presentation.isEnabled = false
          e.presentation.text = GitCompareWithRefBundle.message("user.filter.text")
          e.presentation.description = GitCompareWithRefBundle.message("user.filter.failed", state.reason)
        }
        is AuthorState.Ready -> {
          val index = state.index
          e.presentation.isEnabled = index.authors.isNotEmpty()
          e.presentation.description = GitCompareWithRefBundle.message("user.filter.description")
          val selectedKeys = panel.selectedAuthorKeys
          e.presentation.text = when (selectedKeys.size) {
            0 -> GitCompareWithRefBundle.message("user.filter.text")
            1 -> {
              val key = selectedKeys.first()
              val shortName = index.findAuthor(key)?.shortName ?: key
              val truncated = GitCompareWithRefAuthorIndex.formatToolbarAuthorName(shortName)
              GitCompareWithRefBundle.message("user.filter.text.single", truncated)
            }
            else -> GitCompareWithRefBundle.message("user.filter.text.multiple", selectedKeys.size)
          }
        }
      }
    }

    override fun actionPerformed(e: AnActionEvent) {
      val panel = e.getData(DATA_KEY) ?: return
      val state = panel.authorState as? AuthorState.Ready ?: return
      val index = state.index
      if (index.authors.isEmpty()) return

      val group = DefaultActionGroup()
      group.add(object : DumbAwareToggleAction(GitCompareWithRefBundle.message("user.filter.all")) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
          super.update(e)
          e.presentation.keepPopupOnPerform = KeepPopupOnPerform.Never
        }

        override fun isSelected(e: AnActionEvent): Boolean = panel.selectedAuthorKeys.isEmpty()

        override fun setSelected(e: AnActionEvent, selected: Boolean) {
          if (selected || panel.selectedAuthorKeys.isNotEmpty()) {
            panel.selectedAuthorKeys.clear()
            panel.applyAuthorFilter()
          }
        }
      })
      group.add(Separator.getInstance())

      for (authorEntry in index.authors) {
        group.add(object : DumbAwareToggleAction(authorEntry.popupPresentableText) {
          override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

          override fun update(e: AnActionEvent) {
            super.update(e)
            e.presentation.keepPopupOnPerform = KeepPopupOnPerform.Always
          }

          override fun isSelected(e: AnActionEvent): Boolean = authorEntry.key in panel.selectedAuthorKeys

          override fun setSelected(e: AnActionEvent, selected: Boolean) {
            if (selected) {
              panel.selectedAuthorKeys.add(authorEntry.key)
            }
            else {
              panel.selectedAuthorKeys.remove(authorEntry.key)
            }
            panel.applyAuthorFilter()
          }
        })
      }

      val popup = JBPopupFactory.getInstance().createActionGroupPopup(
        null,
        group,
        e.dataContext,
        JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
        true,
      )
      val component = e.inputEvent?.component ?: e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT)
      if (component != null) {
        popup.showUnderneathOf(component)
      }
      else {
        popup.showInBestPositionFor(e.dataContext)
      }
    }
  }

  companion object {
    val DATA_KEY: DataKey<GitCompareWithRefChangesPanel> =
      DataKey.create("git4idea.actions.compareWithRef.GitCompareWithRefChangesPanel")

    @JvmStatic
    @RequiresEdt
    fun showChanges(
      repository: GitRepository,
      file: VirtualFile,
      refToCompare: GitReference,
      @NlsContexts.DialogTitle dialogTitle: String,
    ) {
      FileDocumentManager.getInstance().saveAllDocuments()
      if (AbstractVcsHelperImpl.showCommittedChangesAsTab()) {
        showAsTab(repository, file, refToCompare, dialogTitle)
      }
      else {
        showDialog(repository, file, refToCompare, dialogTitle)
      }
    }

    private fun showDialog(
      repository: GitRepository,
      file: VirtualFile,
      refToCompare: GitReference,
      @NlsContexts.DialogTitle dialogTitle: String,
    ) {
      val project = repository.project
      val changesPanel = createPanel(repository, file, refToCompare)

      val dialogBuilder = DialogBuilder(project)
      dialogBuilder.setTitle(dialogTitle)
      dialogBuilder.setActionDescriptors(DialogBuilder.CloseDialogAction())
      dialogBuilder.setCenterPanel(changesPanel)
      dialogBuilder.setPreferredFocusComponent(changesPanel.getChangesBrowser().preferredFocusedComponent)
      dialogBuilder.addDisposable(changesPanel)
      dialogBuilder.setDimensionServiceKey("Git.DiffForPathsDialog")
      dialogBuilder.showNotModal()
    }

    private fun showAsTab(
      repository: GitRepository,
      file: VirtualFile,
      refToCompare: GitReference,
      @NlsContexts.DialogTitle dialogTitle: String,
    ) {
      val project = repository.project
      val changesPanel = createPanel(repository, file, refToCompare)

      val changesBrowser = changesPanel.getChangesBrowser()
      val diffPreview = ChangesBrowserToolWindow.createDiffPreview(project, changesBrowser, changesPanel)
      changesBrowser.setShowDiffActionPreview(diffPreview)

      val content = ContentFactory.getInstance().createContent(changesPanel, dialogTitle, false)
      content.preferredFocusableComponent = changesBrowser.preferredFocusedComponent
      content.setDisposer(changesPanel)

      ChangesBrowserToolWindow.showTab(project, content)
    }

    private fun createPanel(
      repository: GitRepository,
      file: VirtualFile,
      refToCompare: GitReference,
    ): GitCompareWithRefChangesPanel {
      val panel = GitCompareWithRefChangesPanel(repository, file, refToCompare)
      panel.reloadChanges()
      return panel
    }
  }
}
