// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.actions.compareWithRef

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GitCompareWithRefAuthorIndexTest {
  private val rootPath = "/workspace/demo"

  @Test
  fun `orders authors by file count descending then display name ascending`() {
    val scenario = createDemoScenario(rootPath)
    val presentableList = scenario.index.authors.map { it.popupPresentableText }

    assertEquals(
      listOf(
        "Alice <alice@example.com> (3)",
        "Bob <bob@example.com> (2)",
        "Carol <carol@example.com> (1)",
        "Dev <dev@example.com> (1)",
      ),
      presentableList,
    )
  }

  @Test
  fun `matches both before path and after path and cross commit renames`() {
    val renamedChange = change(rootPath, "step1.txt", "step3.txt")
    val alice = GitCompareAuthor("Alice", "alice@example.com")
    val bob = GitCompareAuthor("Bob", "bob@example.com")
    val carol = GitCompareAuthor("Carol", "carol@example.com")

    val commits = listOf(
      CommitFileTouch(alice, listOf(PathPair("step1.txt", "step2.txt"))),
      CommitFileTouch(bob, listOf(PathPair("step2.txt", "step2.txt"))),
      CommitFileTouch(carol, listOf(PathPair("step2.txt", "step3.txt"))),
    )

    val index = GitCompareWithRefAuthorIndex.build(
      rootPath = rootPath,
      diffChanges = listOf(renamedChange),
      commits = commits,
      uncommittedChanges = emptyList(),
      currentUser = null,
    )

    assertEquals(listOf(renamedChange), index.filterChanges(setOf("alice@example.com")))
    assertEquals(listOf(renamedChange), index.filterChanges(setOf("bob@example.com")))
    assertEquals(listOf(renamedChange), index.filterChanges(setOf("carol@example.com")))
  }

  @Test
  fun `attributes uncommitted changes to uncommitted pseudo author when current user is missing`() {
    val dirtyChange = change(rootPath, "keep.txt", "keep.txt")
    val index = GitCompareWithRefAuthorIndex.build(
      rootPath = rootPath,
      diffChanges = listOf(dirtyChange),
      commits = emptyList(),
      uncommittedChanges = listOf(PathPair("keep.txt", "keep.txt")),
      currentUser = null,
    )

    assertEquals(1, index.authors.size)
    val entry = index.authors.single()
    assertEquals("(uncommitted)", entry.displayName)
    assertEquals("(uncommitted) (1)", entry.popupPresentableText)
    assertEquals(listOf(dirtyChange), index.filterChanges(setOf(entry.key)))
  }

  @Test
  fun `attributes unmatched changes to unknown pseudo author`() {
    val mergeOnlyChange = change(rootPath, null, "from-merge.txt")
    val index = GitCompareWithRefAuthorIndex.build(
      rootPath = rootPath,
      diffChanges = listOf(mergeOnlyChange),
      commits = emptyList(),
      uncommittedChanges = emptyList(),
      currentUser = null,
    )

    assertEquals(1, index.authors.size)
    val entry = index.authors.single()
    assertEquals("(unknown)", entry.displayName)
    assertEquals("(unknown) (1)", entry.popupPresentableText)
    assertEquals(listOf(mergeOnlyChange), index.filterChanges(setOf(entry.key)))
  }

  @Test
  fun `merges authors with same email in different case`() {
    val change1 = change(rootPath, "a.txt", "a.txt")
    val change2 = change(rootPath, "b.txt", "b.txt")

    val commits = listOf(
      CommitFileTouch(GitCompareAuthor("Alice", "Alice@Example.COM"), listOf(PathPair("a.txt", "a.txt"))),
      CommitFileTouch(GitCompareAuthor("Alice", "alice@example.com"), listOf(PathPair("b.txt", "b.txt"))),
    )

    val index = GitCompareWithRefAuthorIndex.build(
      rootPath = rootPath,
      diffChanges = listOf(change1, change2),
      commits = commits,
      uncommittedChanges = emptyList(),
      currentUser = null,
    )

    assertEquals(1, index.authors.size)
    val entry = index.authors.single()
    assertEquals("alice@example.com", entry.key)
    assertEquals(2, entry.fileCount)
    assertEquals(listOf(change1, change2), index.filterChanges(setOf("alice@example.com")))
  }

  @Test
  fun `takes union when multiple authors are selected`() {
    val scenario = createDemoScenario(rootPath)
    val unionChanges = scenario.index.filterChanges(setOf("carol@example.com", "dev@example.com"))

    assertEquals(
      """
      [修改]
      keep.txt

      [删除]
      master-only.txt
      """.trimIndent(),
      GitCompareWithRefExporter.exportChangesByType(rootPath, unionChanges),
    )
  }

  @Test
  fun `truncates toolbar author name longer than 20 characters`() {
    assertEquals("Short Name", GitCompareWithRefAuthorIndex.formatToolbarAuthorName("Short Name"))
    assertEquals("12345678901234567890", GitCompareWithRefAuthorIndex.formatToolbarAuthorName("12345678901234567890"))
    assertEquals("12345678901234567890\u2026", GitCompareWithRefAuthorIndex.formatToolbarAuthorName("123456789012345678901"))
  }
}
