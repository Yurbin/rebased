// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.actions.compareWithRef

import com.intellij.ide.DataManager
import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.actionSystem.impl.PopupShowingTimeTracker
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.ui.popup.ListPopup
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.vcs.ui.FlatSpeedSearchPopup
import com.intellij.ui.ClientProperty
import com.intellij.util.ui.FilterComponent
import com.intellij.util.ui.JBDimension
import com.intellij.util.ui.UIUtil
import com.intellij.vcs.log.VcsLogBundle
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Supplier
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.ToolTipManager

/**
 * Toolbar filter component for compare-with-ref authors, aligned with the VCS Log user filter.
 */
internal class GitCompareWithRefUserFilterComponent(
  private val panelSupplier: () -> GitCompareWithRefChangesPanel?,
) : FilterComponent(VcsLogBundle.messagePointer("vcs.log.user.filter.label")) {
  private val changeListeners = CopyOnWriteArrayList<Runnable>()
  private var registeredWithPanel = false

  init {
    setShowPopupAction { showPopupMenu() }
  }

  override fun initUi(): JComponent {
    val component = super.initUi()
    val toolTipManager = ToolTipManager.sharedInstance()
    toolTipManager.registerComponent(this)
    for (child in components) {
      if (child is JLabel) {
        toolTipManager.registerComponent(child)
      }
    }
    ensureRegisteredWithPanel()
    refreshUiState()
    return component
  }

  private fun ensureRegisteredWithPanel() {
    if (registeredWithPanel) return
    val panel = panelSupplier() ?: return
    panel.registerFilterComponent(this)
    registeredWithPanel = true
  }

  fun refreshUiState() {
    ensureRegisteredWithPanel()
    val panel = panelSupplier()
    if (panel != null) {
      isEnabled = panel.isUserFilterEnabled()
    }
    for (listener in changeListeners) {
      listener.run()
    }
    revalidate()
    repaint()
  }

  override fun getCurrentText(): String {
    val panel = panelSupplier() ?: return getEmptyFilterValue()
    val values = panel.filterValues
    return if (values.isEmpty()) getEmptyFilterValue() else GitCompareWithRefUserFilter.displayText(values)
  }

  override fun getEmptyFilterValue(): String = ""

  override fun isValueSelected(): Boolean {
    val panel = panelSupplier() ?: return false
    return panel.filterValues.isNotEmpty()
  }

  override fun installChangeListener(onChange: Runnable) {
    changeListeners.add(onChange)
  }

  override fun getToolTipText(): @NlsContexts.Tooltip String? {
    val panel = panelSupplier() ?: return null
    return when (val state = panel.authorState) {
      is GitCompareWithRefChangesPanel.AuthorState.Loading ->
        GitCompareWithRefBundle.message("user.filter.loading")
      is GitCompareWithRefChangesPanel.AuthorState.Failed ->
        GitCompareWithRefBundle.message("user.filter.failed", state.reason)
      is GitCompareWithRefChangesPanel.AuthorState.Ready -> {
        val values = panel.filterValues
        if (values.isEmpty()) null else GitCompareWithRefUserFilter.tooltip(values)
      }
    }
  }

  override fun createResetAction(): Runnable {
    return Runnable {
      val panel = panelSupplier() ?: return@Runnable
      panel.updateFilterValues(emptyList())
    }
  }

  fun showPopupMenu() {
    val panel = panelSupplier() ?: return
    val state = panel.authorState as? GitCompareWithRefChangesPanel.AuthorState.Ready ?: return
    val userFilter = GitCompareWithRefUserFilter(state.index)

    val start = IdeEventQueue.getInstance().popupTriggerTime
    val popup = createPopupMenu(panel, userFilter)
    PopupShowingTimeTracker.showElapsedMillisIfConfigured(start, popup)
    popup.showUnderneathOf(this)
  }

  private fun createPopupMenu(
    panel: GitCompareWithRefChangesPanel,
    userFilter: GitCompareWithRefUserFilter,
  ): ListPopup {
    val actionGroup = createActionGroup(panel, userFilter)
    val speedSearchGroup = createSpeedSearchActionGroup(userFilter)
    return UserSpeedSearchPopup(
      DefaultActionGroup(actionGroup, speedSearchGroup),
      DataManager.getInstance().getDataContext(this),
    )
  }

  private fun createActionGroup(
    panel: GitCompareWithRefChangesPanel,
    userFilter: GitCompareWithRefUserFilter,
  ): ActionGroup {
    val group = ArrayList<AnAction>()
    val meText = me()
    val variants = ArrayList<String>()
    variants.add(meText)
    variants.addAll(userFilter.allValues())
    group.add(SelectMultipleValuesAction(variants))
    if (userFilter.currentUserKey != null) {
      group.add(PredefinedValueAction(listOf(meText), Supplier { meText }, addToRecent = false))
    }
    group.addAll(createRecentItemsActionGroup(panel))
    return DefaultActionGroup(group)
  }

  private fun createRecentItemsActionGroup(panel: GitCompareWithRefChangesPanel): List<AnAction> {
    val group = ArrayList<AnAction>()
    val recentlyFiltered = GitCompareWithRefRecentFilters.getInstance(panel.project).get()
    if (recentlyFiltered.isNotEmpty()) {
      group.add(Separator.create(VcsLogBundle.message("vcs.log.filter.recent")))
      for (recentGroup in recentlyFiltered) {
        if (recentGroup.isNotEmpty()) {
          group.add(PredefinedValueAction(recentGroup, displayName = null, addToRecent = true))
        }
      }
      group.add(Separator.getInstance())
    }
    return group
  }

  private fun createSpeedSearchActionGroup(userFilter: GitCompareWithRefUserFilter): ActionGroup {
    val group = ArrayList<AnAction>()
    val meText = me()
    if (userFilter.currentUserKey != null) {
      group.add(SpeedsearchPredefinedValueAction(meText, addToRecent = false))
      group.add(Separator.getInstance())
    }
    for (user in userFilter.allValues()) {
      group.add(SpeedsearchPredefinedValueAction(user, addToRecent = true))
    }
    return DefaultActionGroup(group)
  }

  private fun rememberValues(panel: GitCompareWithRefChangesPanel, values: List<String>) {
    if (values.isNotEmpty() && values != listOf(me())) {
      GitCompareWithRefRecentFilters.getInstance(panel.project).add(values)
    }
  }

  private fun getActionName(values: List<String>): String {
    if (values.size == 1) return values.first()
    return GitCompareWithRefUserFilter.displayText(values, 2 * GitCompareWithRefUserFilter.MAX_FILTER_VALUE_LENGTH)
  }

  private fun me(): String = VcsLogBundle.message("vcs.log.user.filter.me")

  private open inner class PredefinedValueAction(
    val values: List<String>,
    displayName: Supplier<String>? = null,
    private val addToRecent: Boolean = true,
  ) : DumbAwareAction(null, GitCompareWithRefUserFilter.tooltip(values), null) {
    init {
      templatePresentation.setText(displayName ?: Supplier { getActionName(values) }, false)
    }

    override fun actionPerformed(e: AnActionEvent) {
      val panel = panelSupplier() ?: return
      panel.updateFilterValues(values)
      if (addToRecent) {
        rememberValues(panel, values)
      }
    }
  }

  private inner class SpeedsearchPredefinedValueAction(
    user: String,
    addToRecent: Boolean = true,
  ) : PredefinedValueAction(listOf(user), Supplier { user }, addToRecent), FlatSpeedSearchPopup.SpeedsearchAction

  private inner class SelectMultipleValuesAction(
    private val variants: Collection<String>,
  ) : DumbAwareAction(VcsLogBundle.messagePointer("vcs.log.filter.action.select")) {
    override fun actionPerformed(e: AnActionEvent) {
      val panel = panelSupplier() ?: return
      val initialText = panel.filterValues.joinToString("\n")
      val popupBuilder = MultilinePopupBuilder(panel.project, variants, initialText, null)
      val popup = popupBuilder.createPopup()
      popup.addListener(object : JBPopupListener {
        override fun onClosed(event: LightweightWindowEvent) {
          if (event.isOk) {
            val selectedValues = popupBuilder.selectedValues
            if (selectedValues.isEmpty()) {
              panel.updateFilterValues(emptyList())
            }
            else {
              panel.updateFilterValues(selectedValues)
              rememberValues(panel, selectedValues)
            }
          }
        }
      })
      popup.showUnderneathOf(this@GitCompareWithRefUserFilterComponent)
    }
  }

  private class UserSpeedSearchPopup(
    actionGroup: DefaultActionGroup,
    dataContext: DataContext,
  ) : FlatSpeedSearchPopup(
    null,
    actionGroup,
    dataContext,
    ActionPlaces.getPopupPlace("Git.CompareWithRef.UserWidget"),
    null,
    false,
  ) {
    init {
      setMinimumSize(JBDimension(200, 0))
    }

    override fun shouldBeShowing(action: AnAction): Boolean {
      if (!super.shouldBeShowing(action)) return false
      return if (speedSearch.isHoldingFilter) {
        if (action is PredefinedValueAction) {
          action is SpeedsearchAction || action.values.size > 1
        }
        else {
          true
        }
      }
      else {
        !isSpeedsearchAction(action)
      }
    }
  }
}

/**
 * Toolbar action that hosts [GitCompareWithRefUserFilterComponent] in the compare-with-ref changes browser.
 */
internal class GitCompareWithRefUserFilterAction(
  private val panelSupplier: () -> GitCompareWithRefChangesPanel?,
) : DumbAwareAction(VcsLogBundle.messagePointer("vcs.log.user.filter.action.text")), CustomComponentAction {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

  override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
    return GitCompareWithRefUserFilterComponent(panelSupplier).initUi()
  }

  override fun updateCustomComponent(component: JComponent, presentation: Presentation) {
    component.isEnabled = presentation.isEnabled
    if (component is GitCompareWithRefUserFilterComponent) {
      component.refreshUiState()
    }
  }

  override fun update(e: AnActionEvent) {
    val panel = panelSupplier() ?: e.getData(GitCompareWithRefChangesPanel.DATA_KEY)
    if (panel == null) {
      e.presentation.isEnabledAndVisible = false
      return
    }
    e.presentation.isVisible = true
    e.presentation.isEnabled = panel.isUserFilterEnabled()
  }

  override fun actionPerformed(e: AnActionEvent) {
    val panel = panelSupplier() ?: e.getData(GitCompareWithRefChangesPanel.DATA_KEY) ?: return
    if (!panel.isUserFilterEnabled()) return
    val component = UIUtil.uiTraverser(panel.getChangesBrowser()).traverse().find { c ->
      ClientProperty.get(c, CustomComponentAction.ACTION_KEY) === this
    }
    if (component is GitCompareWithRefUserFilterComponent) {
      component.showPopupMenu()
    }
  }
}
