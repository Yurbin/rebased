// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.actions.compareWithRef

import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vfs.VirtualFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GitCompareWithRefExportTest {
  private val rootPath = "/workspace/demo"

  @Test
  fun `exports demo scenario for all filter combinations`() {
    val scenario = createDemoScenario(rootPath)
    val index = scenario.index

    assertEquals(
      """
      [新增]
      src/新文件.kt

      [修改]
      docs/说明.md
      keep.txt
      mod.txt

      [重命名/移动]
      old.txt -> renamed/new.txt

      [删除]
      del.txt
      master-only.txt
      """.trimIndent(),
      GitCompareWithRefExporter.exportChangesByType(rootPath, index.filterChanges(emptySet())),
    )

    assertEquals(
      """
      [新增]
      src/新文件.kt

      [修改]
      docs/说明.md
      mod.txt
      """.trimIndent(),
      GitCompareWithRefExporter.exportChangesByType(rootPath, index.filterChanges(setOf("alice@example.com"))),
    )

    assertEquals(
      """
      [重命名/移动]
      old.txt -> renamed/new.txt

      [删除]
      del.txt
      """.trimIndent(),
      GitCompareWithRefExporter.exportChangesByType(rootPath, index.filterChanges(setOf("bob@example.com"))),
    )

    assertEquals(
      """
      [删除]
      master-only.txt
      """.trimIndent(),
      GitCompareWithRefExporter.exportChangesByType(rootPath, index.filterChanges(setOf("carol@example.com"))),
    )

    assertEquals(
      """
      [修改]
      keep.txt
      """.trimIndent(),
      GitCompareWithRefExporter.exportChangesByType(rootPath, index.filterChanges(setOf("dev@example.com"))),
    )

    assertEquals(
      """
      [新增]
      src/新文件.kt

      [修改]
      docs/说明.md
      mod.txt

      [重命名/移动]
      old.txt -> renamed/new.txt

      [删除]
      del.txt
      """.trimIndent(),
      GitCompareWithRefExporter.exportChangesByType(
        rootPath,
        index.filterChanges(setOf("alice@example.com", "bob@example.com")),
      ),
    )
  }

  @Test
  fun `omits empty groups and returns empty string for empty input`() {
    assertEquals("", GitCompareWithRefExporter.exportChangesByType(rootPath, emptyList()))

    val onlyAdded = listOf(change(rootPath, null, "only-added.kt"))
    assertEquals(
      """
      [新增]
      only-added.kt
      """.trimIndent(),
      GitCompareWithRefExporter.exportChangesByType(rootPath, onlyAdded),
    )
  }

  @Test
  fun `normalizes windows separators and preserves chinese and spaces`() {
    val winRoot = "C:\\Users\\dev\\repo\\"
    val changes = listOf(
      change(winRoot, null, "目录 A\\子目录\\新建 文件.txt"),
      change(winRoot, "旧目录\\旧 文件.txt", "新目录\\新 文件.txt"),
    )

    assertEquals(
      """
      [新增]
      目录 A/子目录/新建 文件.txt

      [重命名/移动]
      旧目录/旧 文件.txt -> 新目录/新 文件.txt
      """.trimIndent(),
      GitCompareWithRefExporter.exportChangesByType(winRoot, changes),
    )
  }

  @Test
  fun `sorts lines within each group using natural case sensitive order`() {
    val changes = listOf(
      change(rootPath, "z.txt", "z.txt"),
      change(rootPath, "a.txt", "a.txt"),
      change(rootPath, "B.txt", "B.txt"),
      change(rootPath, "A.txt", "A.txt"),
    )

    assertEquals(
      """
      [修改]
      A.txt
      B.txt
      a.txt
      z.txt
      """.trimIndent(),
      GitCompareWithRefExporter.exportChangesByType(rootPath, changes),
    )
  }
}

internal data class DemoScenario(
  val diffChanges: List<Change>,
  val index: GitCompareWithRefAuthorIndex,
)

internal fun createDemoScenario(rootPath: String): DemoScenario {
  val addedNewFile = change(rootPath, null, "src/新文件.kt")
  val modifiedDoc = change(rootPath, "docs/说明.md", "docs/说明.md")
  val modifiedKeep = change(rootPath, "keep.txt", "keep.txt")
  val modifiedMod = change(rootPath, "mod.txt", "mod.txt")
  val renamedOldToNew = change(rootPath, "old.txt", "renamed/new.txt")
  val deletedDel = change(rootPath, "del.txt", null)
  val deletedMasterOnly = change(rootPath, "master-only.txt", null)

  val diffChanges = listOf(
    addedNewFile,
    modifiedDoc,
    modifiedKeep,
    modifiedMod,
    renamedOldToNew,
    deletedDel,
    deletedMasterOnly,
  )

  val alice = GitCompareAuthor("Alice", "alice@example.com")
  val bob = GitCompareAuthor("Bob", "bob@example.com")
  val carol = GitCompareAuthor("Carol", "carol@example.com")
  val dev = GitCompareAuthor("Dev", "dev@example.com")

  val commits = listOf(
    CommitFileTouch(
      author = alice,
      files = listOf(
        PathPair(null, "src/新文件.kt"),
        PathPair("mod.txt", "mod.txt"),
      ),
    ),
    CommitFileTouch(
      author = bob,
      files = listOf(
        PathPair("old.txt", "renamed/new.txt"),
        PathPair("del.txt", null),
      ),
    ),
    CommitFileTouch(
      author = alice,
      files = listOf(
        PathPair("docs/说明.md", "docs/说明.md"),
      ),
    ),
    CommitFileTouch(
      author = carol,
      files = listOf(
        PathPair(null, "master-only.txt"),
      ),
    ),
  )

  val uncommitted = listOf(
    PathPair("keep.txt", "keep.txt"),
  )

  val index = GitCompareWithRefAuthorIndex.build(
    rootPath = rootPath,
    diffChanges = diffChanges,
    commits = commits,
    uncommittedChanges = uncommitted,
    currentUser = dev,
  )
  return DemoScenario(diffChanges, index)
}

internal fun change(rootPath: String, beforeRelPath: String?, afterRelPath: String?): Change {
  val beforeRev = beforeRelPath?.let { TestContentRevision(joinPath(rootPath, it)) }
  val afterRev = afterRelPath?.let { TestContentRevision(joinPath(rootPath, it)) }
  return Change(beforeRev, afterRev)
}

private fun joinPath(rootPath: String, relPath: String): String {
  val sep = if (rootPath.contains('\\')) '\\' else '/'
  return rootPath.trimEnd('/', '\\') + sep + relPath
}

private class TestContentRevision(path: String) : ContentRevision {
  private val filePath: FilePath = object : LocalFilePath(path, false, null) {
    override fun getVirtualFile(): VirtualFile? = null
  }

  override fun getContent(): String = ""

  override fun getFile(): FilePath = filePath

  override fun getRevisionNumber(): VcsRevisionNumber = VcsRevisionNumber.NULL
}
