// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.actions.compareWithRef

import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vfs.VirtualFile

/**
 * Formats changes as relative paths grouped by change type.
 */
internal object GitCompareWithRefExporter {
  fun exportChangesByType(root: VirtualFile, changes: Collection<Change>): String {
    return exportChangesByType(root.path, changes)
  }

  fun exportChangesByType(rootPath: String, changes: Collection<Change>): String {
    val added = mutableListOf<String>()
    val modified = mutableListOf<String>()
    val moved = mutableListOf<String>()
    val deleted = mutableListOf<String>()

    for (change in changes) {
      val beforeRevision = change.beforeRevision
      val afterRevision = change.afterRevision
      when {
        beforeRevision == null && afterRevision != null -> {
          added.add(toRelativePath(rootPath, afterRevision.file))
        }
        afterRevision == null && beforeRevision != null -> {
          deleted.add(toRelativePath(rootPath, beforeRevision.file))
        }
        beforeRevision != null && afterRevision != null -> {
          val beforePath = toRelativePath(rootPath, beforeRevision.file)
          val afterPath = toRelativePath(rootPath, afterRevision.file)
          if (beforePath != afterPath) {
            moved.add("$beforePath -> $afterPath")
          }
          else {
            modified.add(afterPath)
          }
        }
      }
    }

    val groups = listOf(
      GitCompareWithRefBundle.message("export.group.added") to added,
      GitCompareWithRefBundle.message("export.group.modified") to modified,
      GitCompareWithRefBundle.message("export.group.moved") to moved,
      GitCompareWithRefBundle.message("export.group.deleted") to deleted,
    )

    return groups
      .filter { (_, items) -> items.isNotEmpty() }
      .joinToString("\n\n") { (header, items) ->
        val sortedItems = items.sorted()
        buildString {
          append(header)
          for (item in sortedItems) {
            append('\n')
            append(item)
          }
        }
      }
  }

  fun toRelativePath(rootPath: String, filePath: FilePath): String {
    return toRelativePath(rootPath, filePath.path)
  }

  fun toRelativePath(rootPath: String, fullPath: String): String {
    val normalizedRoot = rootPath.replace('\\', '/').trimEnd('/')
    val normalizedPath = fullPath.replace('\\', '/')
    if (normalizedRoot.isNotEmpty() && normalizedPath.startsWith("$normalizedRoot/")) {
      return normalizedPath.substring(normalizedRoot.length + 1)
    }
    if (normalizedPath == normalizedRoot) {
      return ""
    }
    return normalizedPath.trimStart('/')
  }
}
