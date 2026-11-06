#!/usr/bin/env bash
# Create an offline demo Git repository to verify Compare with Branch or Tag user filtering and path export.
set -euo pipefail

TARGET_DIR="${1:-}"
if [ -z "$TARGET_DIR" ]; then
  echo "Usage: $0 <target-dir>" >&2
  exit 1
fi

if [ -e "$TARGET_DIR" ]; then
  echo "ERROR: Target path already exists: $TARGET_DIR" >&2
  exit 1
fi

mkdir -p "$TARGET_DIR"
cd "$TARGET_DIR"

git init -q
git checkout -q -b master

write_multiline() {
  file="$1"
  title="$2"
  mkdir -p "$(dirname "$file")"
  cat > "$file" <<EOF
$title - line 1
$title - line 2
$title - line 3
$title - line 4
$title - line 5
$title - line 6
$title - line 7
$title - line 8
EOF
}

# C0 on master by Base
write_multiline "keep.txt" "keep.txt initial content"
write_multiline "old.txt" "old.txt shared content for rename detection"
write_multiline "del.txt" "del.txt initial content"
write_multiline "mod.txt" "mod.txt initial content"
write_multiline "docs/说明.md" "docs/说明.md 初始说明文档内容"

git add keep.txt old.txt del.txt mod.txt "docs/说明.md"
GIT_AUTHOR_NAME="Base" GIT_AUTHOR_EMAIL="base@example.com" \
GIT_COMMITTER_NAME="Base" GIT_COMMITTER_EMAIL="base@example.com" \
GIT_AUTHOR_DATE="2026-01-01T10:00:00Z" GIT_COMMITTER_DATE="2026-01-01T10:00:00Z" \
git commit -q -m "C0: initial base files"

# Create feature branch from C0
git checkout -q -b feature

# C1 on feature by Alice: add src/新文件.kt, modify mod.txt
write_multiline "src/新文件.kt" "package demo"
printf "modified by Alice\n" >> "mod.txt"
git add "src/新文件.kt" "mod.txt"
GIT_AUTHOR_NAME="Alice" GIT_AUTHOR_EMAIL="alice@example.com" \
GIT_COMMITTER_NAME="Alice" GIT_COMMITTER_EMAIL="alice@example.com" \
GIT_AUTHOR_DATE="2026-01-02T10:00:00Z" GIT_COMMITTER_DATE="2026-01-02T10:00:00Z" \
git commit -q -m "C1: Alice adds new file and updates mod.txt"

# C2 on feature by Bob: rename old.txt -> renamed/new.txt without content change, delete del.txt
mkdir -p renamed
git mv old.txt renamed/new.txt
git rm -q del.txt
GIT_AUTHOR_NAME="Bob" GIT_AUTHOR_EMAIL="bob@example.com" \
GIT_COMMITTER_NAME="Bob" GIT_COMMITTER_EMAIL="bob@example.com" \
GIT_AUTHOR_DATE="2026-01-03T10:00:00Z" GIT_COMMITTER_DATE="2026-01-03T10:00:00Z" \
git commit -q -m "C2: Bob renames old.txt and deletes del.txt"

# C3 on feature by Alice: modify docs/说明.md
printf "Alice 补充的说明内容\n" >> "docs/说明.md"
git add "docs/说明.md"
GIT_AUTHOR_NAME="Alice" GIT_AUTHOR_EMAIL="alice@example.com" \
GIT_COMMITTER_NAME="Alice" GIT_COMMITTER_EMAIL="alice@example.com" \
GIT_AUTHOR_DATE="2026-01-04T10:00:00Z" GIT_COMMITTER_DATE="2026-01-04T10:00:00Z" \
git commit -q -m "C3: Alice updates docs/说明.md"

# C4 on master by Carol: add master-only.txt
git checkout -q master
write_multiline "master-only.txt" "master-only.txt added on master by Carol"
git add "master-only.txt"
GIT_AUTHOR_NAME="Carol" GIT_AUTHOR_EMAIL="carol@example.com" \
GIT_COMMITTER_NAME="Carol" GIT_COMMITTER_EMAIL="carol@example.com" \
GIT_AUTHOR_DATE="2026-01-05T10:00:00Z" GIT_COMMITTER_DATE="2026-01-05T10:00:00Z" \
git commit -q -m "C4: Carol adds master-only.txt on master"

# Switch back to feature, configure local user Dev, and modify keep.txt without committing
git checkout -q feature
git config user.name "Dev"
git config user.email "dev@example.com"
printf "uncommitted change by Dev\n" >> "keep.txt"

echo "Demo repository created at: $(pwd)"
echo "Current branch: $(git branch --show-current)"
echo "Compare target: master"
