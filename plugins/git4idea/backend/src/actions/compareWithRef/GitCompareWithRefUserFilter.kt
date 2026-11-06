// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.actions.compareWithRef

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vcs.changes.Change
import com.intellij.vcs.log.VcsLogBundle
import com.intellij.vcs.log.util.VcsUserUtil
import java.util.TreeSet

/**
 * Matches compare-with-ref user filter values against commit authors.
 */
internal class GitCompareWithRefUserFilter(
  val index: GitCompareWithRefAuthorIndex,
  val currentUserKey: String? = index.currentUserKey,
) {
  fun allValues(): List<String> {
    val sortedUniqueUsers = TreeSet<String>()
    for (entry in index.authors) {
      val presentation = formatUserPresentation(entry.author)
      if (presentation.isNotEmpty()) {
        sortedUniqueUsers.add(presentation)
      }
    }
    return ArrayList(sortedUniqueUsers)
  }

  fun resolveAuthorKeys(values: List<String>): Set<String> {
    if (values.isEmpty()) {
      return emptySet()
    }
    val meText = VcsLogBundle.message("vcs.log.user.filter.me")
    val matchedKeys = LinkedHashSet<String>()
    for (rawValue in values) {
      val trimmed = rawValue.trim()
      if (trimmed.isEmpty()) continue
      if (trimmed == meText) {
        if (currentUserKey != null) {
          matchedKeys.add(currentUserKey)
        }
        continue
      }

      val targetStandardName = VcsUserUtil.getNameInStandardForm(trimmed)
      for (entry in index.authors) {
        val authorPresentation = formatUserPresentation(entry.author)
        val authorStandardName = VcsUserUtil.getNameInStandardForm(authorPresentation)
        val email = entry.author.email.trim()
        if (authorStandardName == targetStandardName || (email.isNotEmpty() && email.equals(trimmed, ignoreCase = true))) {
          matchedKeys.add(entry.key)
        }
      }
    }
    return matchedKeys
  }

  fun filterChanges(values: List<String>): List<Change> {
    if (values.isEmpty()) {
      return index.allChanges
    }
    val keys = resolveAuthorKeys(values)
    if (keys.isEmpty()) {
      return emptyList()
    }
    return index.filterChanges(keys)
  }

  companion object {
    const val MAX_FILTER_VALUE_LENGTH: Int = 20

    fun formatUserPresentation(author: GitCompareAuthor): @NlsSafe String {
      val name = author.name.trim()
      val email = author.email.trim()
      if (email.isEmpty() && name.startsWith("(") && name.endsWith(")")) {
        return name
      }
      val shortPresentation = VcsUserUtil.getUserName(name, email)
      val firstAndLastName = VcsUserUtil.getFirstAndLastName(shortPresentation) ?: return shortPresentation
      return "${VcsUserUtil.capitalizeName(firstAndLastName.first)} ${VcsUserUtil.capitalizeName(firstAndLastName.second)}"
    }

    fun displayText(values: Collection<String>, maxLength: Int = MAX_FILTER_VALUE_LENGTH): @NlsSafe String {
      if (values.isEmpty()) {
        return ""
      }
      val text = if (values.size == 1) values.first() else StringUtil.join(values, "|")
      return StringUtil.shortenTextWithEllipsis(text, maxLength, 0, true)
    }

    fun tooltip(values: Collection<String>): @NlsSafe String {
      return StringUtil.join(values, ", ")
    }
  }
}

/**
 * Stores recent compare-with-ref user filter groups in memory for the current project.
 */
@Service(Service.Level.PROJECT)
internal class GitCompareWithRefRecentFilters {
  private val recentGroups = ArrayDeque<List<String>>()

  @Synchronized
  fun add(values: Collection<String>) {
    val group = values.toList()
    if (group.isEmpty()) return
    recentGroups.remove(group)
    recentGroups.addFirst(group)
    while (recentGroups.size > MAX_RECENT_GROUPS) {
      recentGroups.removeLast()
    }
  }

  @Synchronized
  fun get(): List<List<String>> = recentGroups.toList()

  companion object {
    private const val MAX_RECENT_GROUPS = 10

    fun getInstance(project: Project): GitCompareWithRefRecentFilters =
      project.getService(GitCompareWithRefRecentFilters::class.java)
  }
}
