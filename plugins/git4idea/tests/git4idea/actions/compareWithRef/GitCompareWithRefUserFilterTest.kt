// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.actions.compareWithRef

import com.intellij.vcs.log.VcsLogBundle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GitCompareWithRefUserFilterTest {
  private val rootPath = "/workspace/demo"

  @Test
  fun `collects sorted unique user names for demo scenario`() {
    val scenario = createDemoScenario(rootPath)
    val filter = GitCompareWithRefUserFilter(scenario.index)

    assertEquals(listOf("Alice", "Bob", "Carol", "Dev"), filter.allValues())
  }

  @Test
  fun `filters and exports demo scenario for single and multiple user values`() {
    val scenario = createDemoScenario(rootPath)
    val filter = GitCompareWithRefUserFilter(scenario.index)

    assertEquals(
      """
      [新增]
      src/新文件.kt

      [修改]
      docs/说明.md
      mod.txt
      """.trimIndent(),
      GitCompareWithRefExporter.exportChangesByType(rootPath, filter.filterChanges(listOf("Alice"))),
    )

    assertEquals(
      """
      [重命名/移动]
      old.txt -> renamed/new.txt

      [删除]
      del.txt
      """.trimIndent(),
      GitCompareWithRefExporter.exportChangesByType(rootPath, filter.filterChanges(listOf("Bob"))),
    )

    assertEquals(
      """
      [删除]
      master-only.txt
      """.trimIndent(),
      GitCompareWithRefExporter.exportChangesByType(rootPath, filter.filterChanges(listOf("Carol"))),
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
      GitCompareWithRefExporter.exportChangesByType(rootPath, filter.filterChanges(listOf("Alice", "Bob"))),
    )
  }

  @Test
  fun `resolves me filter value to current user changes`() {
    val scenario = createDemoScenario(rootPath)
    val filter = GitCompareWithRefUserFilter(scenario.index)
    val meText = VcsLogBundle.message("vcs.log.user.filter.me")

    assertEquals(
      """
      [修改]
      keep.txt
      """.trimIndent(),
      GitCompareWithRefExporter.exportChangesByType(rootPath, filter.filterChanges(listOf(meText))),
    )
  }

  @Test
  fun `matches lowercase user name and email address`() {
    val scenario = createDemoScenario(rootPath)
    val filter = GitCompareWithRefUserFilter(scenario.index)

    assertEquals(
      """
      [新增]
      src/新文件.kt

      [修改]
      docs/说明.md
      mod.txt
      """.trimIndent(),
      GitCompareWithRefExporter.exportChangesByType(rootPath, filter.filterChanges(listOf("alice"))),
    )

    assertEquals(
      """
      [重命名/移动]
      old.txt -> renamed/new.txt

      [删除]
      del.txt
      """.trimIndent(),
      GitCompareWithRefExporter.exportChangesByType(rootPath, filter.filterChanges(listOf("bob@example.com"))),
    )
  }

  @Test
  fun `returns empty export when filter value matches nobody`() {
    val scenario = createDemoScenario(rootPath)
    val filter = GitCompareWithRefUserFilter(scenario.index)

    assertEquals("", GitCompareWithRefExporter.exportChangesByType(rootPath, filter.filterChanges(listOf("Nobody"))))
  }

  @Test
  fun `capitalizes two part english name and preserves chinese name`() {
    val change1 = change(rootPath, "a.txt", "a.txt")
    val change2 = change(rootPath, "b.txt", "b.txt")
    val commits = listOf(
      CommitFileTouch(GitCompareAuthor("john smith", "john@example.com"), listOf(PathPair("a.txt", "a.txt"))),
      CommitFileTouch(GitCompareAuthor("张三", "zhangsan@example.com"), listOf(PathPair("b.txt", "b.txt"))),
    )
    val index = GitCompareWithRefAuthorIndex.build(
      rootPath = rootPath,
      diffChanges = listOf(change1, change2),
      commits = commits,
      uncommittedChanges = emptyList(),
      currentUser = null,
    )
    val filter = GitCompareWithRefUserFilter(index)

    assertEquals(listOf("John Smith", "张三"), filter.allValues())
    assertEquals(listOf(change1), filter.filterChanges(listOf("John Smith")))
    assertEquals(listOf(change2), filter.filterChanges(listOf("张三")))
  }

  @Test
  fun `deduplicates authors with same name and different emails and matches both`() {
    val change1 = change(rootPath, "a.txt", "a.txt")
    val change2 = change(rootPath, "b.txt", "b.txt")
    val commits = listOf(
      CommitFileTouch(GitCompareAuthor("Alice", "alice1@example.com"), listOf(PathPair("a.txt", "a.txt"))),
      CommitFileTouch(GitCompareAuthor("Alice", "alice2@example.com"), listOf(PathPair("b.txt", "b.txt"))),
    )
    val index = GitCompareWithRefAuthorIndex.build(
      rootPath = rootPath,
      diffChanges = listOf(change1, change2),
      commits = commits,
      uncommittedChanges = emptyList(),
      currentUser = null,
    )
    val filter = GitCompareWithRefUserFilter(index)

    assertEquals(listOf("Alice"), filter.allValues())
    assertEquals(listOf(change1, change2), filter.filterChanges(listOf("Alice")))
  }

  @Test
  fun `formats display text with pipe and ellipsis and tooltip with comma`() {
    assertEquals("", GitCompareWithRefUserFilter.displayText(emptyList()))
    assertEquals("Alice", GitCompareWithRefUserFilter.displayText(listOf("Alice")))
    assertEquals("Alice|Bob", GitCompareWithRefUserFilter.displayText(listOf("Alice", "Bob")))
    assertEquals(
      "Alexander Hamilton|\u2026",
      GitCompareWithRefUserFilter.displayText(listOf("Alexander Hamilton", "Bob")),
    )
    assertEquals("Alice, Bob", GitCompareWithRefUserFilter.tooltip(listOf("Alice", "Bob")))
  }

  @Test
  fun `keeps at most ten recent filter groups and moves duplicates to front`() {
    val recent = GitCompareWithRefRecentFilters()
    for (i in 1..12) {
      recent.add(listOf("User$i"))
    }

    assertEquals(
      (12 downTo 3).map { listOf("User$it") },
      recent.get(),
    )

    recent.add(listOf("User5"))
    val expected = listOf(listOf("User5")) + (12 downTo 3).filter { it != 5 }.map { listOf("User$it") }
    assertEquals(expected, recent.get())
  }
}
