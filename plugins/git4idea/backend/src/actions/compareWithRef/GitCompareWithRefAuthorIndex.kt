// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.actions.compareWithRef

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcsUtil.VcsUtil
import git4idea.GitReference
import git4idea.GitUserRegistry
import git4idea.GitUtil
import git4idea.changes.GitChangeUtils
import git4idea.history.GitLogUtil
import git4idea.repo.GitRepository
import java.util.Locale

internal data class GitCompareAuthor(
  val name: String,
  val email: String,
) {
  val key: String
    get() {
      val trimmedEmail = email.trim()
      return if (trimmedEmail.isNotEmpty()) {
        trimmedEmail.lowercase(Locale.ROOT)
      }
      else {
        name.trim()
      }
    }

  val displayName: String
    get() {
      val trimmedName = name.trim()
      val trimmedEmail = email.trim()
      return when {
        trimmedName.isNotEmpty() && trimmedEmail.isNotEmpty() -> "$trimmedName <$trimmedEmail>"
        trimmedName.isNotEmpty() -> trimmedName
        else -> trimmedEmail
      }
    }

  val shortName: String
    get() {
      val trimmedName = name.trim()
      return if (trimmedName.isNotEmpty()) trimmedName else email.trim()
    }
}

internal data class PathPair(
  val beforePath: String?,
  val afterPath: String?,
)

internal data class CommitFileTouch(
  val author: GitCompareAuthor,
  val files: List<PathPair>,
)

internal data class AuthorEntry(
  val author: GitCompareAuthor,
  val changes: List<Change>,
) {
  val key: String
    get() = author.key

  val displayName: String
    get() = author.displayName

  val shortName: String
    get() = author.shortName

  val fileCount: Int
    get() = changes.size

  val popupPresentableText: String
    get() = "$displayName ($fileCount)"
}

/**
 * Maps compared changes to commit authors and working tree authors.
 */
internal class GitCompareWithRefAuthorIndex private constructor(
  val allChanges: List<Change>,
  val authors: List<AuthorEntry>,
) {
  private val authorByKey: Map<String, AuthorEntry> = authors.associateBy { it.key }

  fun filterChanges(selectedAuthorKeys: Set<String>): List<Change> {
    if (selectedAuthorKeys.isEmpty()) {
      return allChanges
    }
    val matchedSet = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Change, Boolean>())
    val matchedFallback = HashSet<Change>()
    for (key in selectedAuthorKeys) {
      val entry = authorByKey[key] ?: continue
      for (change in entry.changes) {
        matchedSet.add(change)
        matchedFallback.add(change)
      }
    }
    return allChanges.filter { it in matchedSet || it in matchedFallback }
  }

  fun findAuthor(key: String): AuthorEntry? = authorByKey[key]

  companion object {
    private const val MAX_TOOLBAR_NAME_LENGTH = 20

    fun formatToolbarAuthorName(name: String): String {
      return if (name.length > MAX_TOOLBAR_NAME_LENGTH) {
        "${name.substring(0, MAX_TOOLBAR_NAME_LENGTH)}\u2026"
      }
      else {
        name
      }
    }

    @Throws(VcsException::class)
    fun loadForCompare(
      project: Project,
      repository: GitRepository,
      file: VirtualFile,
      refToCompare: GitReference,
      diffChanges: List<Change>,
    ): GitCompareWithRefAuthorIndex {
      val root = repository.root
      val rootPath = root.path
      val commits = mutableListOf<CommitFileTouch>()

      GitLogUtil.readFullDetails(
        project,
        root,
        { gitCommit ->
          ProgressManager.checkCanceled()
          val author = GitCompareAuthor(gitCommit.author.name, gitCommit.author.email)
          val files = gitCommit.changes.map { change ->
            changeToPathPair(rootPath, change)
          }
          commits.add(CommitFileTouch(author, files))
        },
        "${refToCompare.fullName}...${GitUtil.HEAD}",
        "--no-merges",
      )

      ProgressManager.checkCanceled()
      val scope = listOf(VcsUtil.getFilePath(file))
      val workingTreeChanges = GitChangeUtils.getDiffWithWorkingDir(project, root, GitUtil.HEAD, scope, false)
      val uncommittedPairs = workingTreeChanges.map { change ->
        changeToPathPair(rootPath, change)
      }

      val vcsUser = GitUserRegistry.getInstance(project).getOrReadUser(root)
      val currentUser = vcsUser
        ?.takeIf { it.name.isNotBlank() || it.email.isNotBlank() }
        ?.let { GitCompareAuthor(it.name, it.email) }

      return build(
        rootPath = rootPath,
        diffChanges = diffChanges,
        commits = commits,
        uncommittedChanges = uncommittedPairs,
        currentUser = currentUser,
      )
    }

    fun build(
      rootPath: String,
      diffChanges: List<Change>,
      commits: List<CommitFileTouch>,
      uncommittedChanges: List<PathPair>,
      currentUser: GitCompareAuthor?,
    ): GitCompareWithRefAuthorIndex {
      if (diffChanges.isEmpty()) {
        return GitCompareWithRefAuthorIndex(emptyList(), emptyList())
      }

      val adjacency = HashMap<String, MutableSet<String>>()
      fun linkPaths(pair: PathPair) {
        val before = pair.beforePath?.takeIf { it.isNotEmpty() }
        val after = pair.afterPath?.takeIf { it.isNotEmpty() }
        if (before != null && after != null && before != after) {
          adjacency.getOrPut(before) { HashSet() }.add(after)
          adjacency.getOrPut(after) { HashSet() }.add(before)
        }
      }

      for (commit in commits) {
        for (pair in commit.files) {
          linkPaths(pair)
        }
      }
      for (pair in uncommittedChanges) {
        linkPaths(pair)
      }

      val canonicalAuthors = LinkedHashMap<String, GitCompareAuthor>()
      val authorTouchedPaths = LinkedHashMap<String, MutableSet<String>>()

      fun recordTouches(author: GitCompareAuthor, pairs: List<PathPair>) {
        if (pairs.isEmpty()) return
        val key = author.key
        canonicalAuthors.putIfAbsent(key, author)
        val touched = authorTouchedPaths.getOrPut(key) { HashSet() }
        for (pair in pairs) {
          pair.beforePath?.takeIf { it.isNotEmpty() }?.let { touched.add(it) }
          pair.afterPath?.takeIf { it.isNotEmpty() }?.let { touched.add(it) }
        }
      }

      for (commit in commits) {
        recordTouches(commit.author, commit.files)
      }

      if (uncommittedChanges.isNotEmpty()) {
        val uncommittedAuthor = currentUser
                                ?: GitCompareAuthor(GitCompareWithRefBundle.message("user.uncommitted"), "")
        recordTouches(uncommittedAuthor, uncommittedChanges)
      }

      val authorChanges = LinkedHashMap<String, MutableList<Change>>()
      val unknownAuthor = GitCompareAuthor(GitCompareWithRefBundle.message("user.unknown"), "")

      for (change in diffChanges) {
        val pair = changeToPathPair(rootPath, change)
        val equivalentPaths = collectEquivalentPaths(pair, adjacency)
        var matchedAny = false

        for ((authorKey, touchedPaths) in authorTouchedPaths) {
          if (equivalentPaths.any { it in touchedPaths }) {
            matchedAny = true
            authorChanges.getOrPut(authorKey) { mutableListOf() }.add(change)
          }
        }

        if (!matchedAny) {
          val unknownKey = unknownAuthor.key
          canonicalAuthors.putIfAbsent(unknownKey, unknownAuthor)
          authorChanges.getOrPut(unknownKey) { mutableListOf() }.add(change)
        }
      }

      val entries = authorChanges.mapNotNull { (key, changes) ->
        if (changes.isEmpty()) return@mapNotNull null
        val author = canonicalAuthors[key] ?: return@mapNotNull null
        AuthorEntry(author, changes)
      }.sortedWith(
        compareByDescending<AuthorEntry> { it.fileCount }
          .thenBy { it.displayName }
      )

      return GitCompareWithRefAuthorIndex(diffChanges, entries)
    }

    private fun changeToPathPair(rootPath: String, change: Change): PathPair {
      val before = change.beforeRevision?.file?.let { GitCompareWithRefExporter.toRelativePath(rootPath, it) }
      val after = change.afterRevision?.file?.let { GitCompareWithRefExporter.toRelativePath(rootPath, it) }
      return PathPair(before, after)
    }

    private fun collectEquivalentPaths(
      pair: PathPair,
      adjacency: Map<String, Set<String>>,
    ): Set<String> {
      val visited = HashSet<String>()
      val queue = ArrayDeque<String>()

      pair.beforePath?.takeIf { it.isNotEmpty() }?.let {
        if (visited.add(it)) queue.add(it)
      }
      pair.afterPath?.takeIf { it.isNotEmpty() }?.let {
        if (visited.add(it)) queue.add(it)
      }

      while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        val neighbors = adjacency[current] ?: continue
        for (neighbor in neighbors) {
          if (visited.add(neighbor)) {
            queue.add(neighbor)
          }
        }
      }
      return visited
    }
  }
}
