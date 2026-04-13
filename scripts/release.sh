#!/bin/bash
set -euo pipefail

# release.sh — Automates the GamePerf Desktop release workflow.
#
# Usage: ./scripts/release.sh [version]
#   e.g.: ./scripts/release.sh 4.1.0
#
# Steps:
#   1. Validates the version format (X.Y.Z)
#   2. Updates gradle.properties with the new version
#   3. Creates a conventional commit
#   4. Tags with v{version}
#   5. Pushes commit + tag to origin (triggers CI)
#   6. Waits for CI to complete (polls gh run)
#   7. Builds the local macos-x64 uber JAR
#   8. Prompts for CHANGELOG excerpt to use as release body
#   9. Uploads the local JAR and overwrites the release body
#
# Requirements: git, gh CLI (authenticated), ./gradlew, macOS (for local build)
#
# WARNING: This script pushes to main. Use with care.

VERSION="${1:-}"

if [ -z "$VERSION" ]; then
    echo "Usage: $0 <version>"
    echo "  e.g.: $0 4.1.0"
    exit 1
fi

# Validate semver format
if ! echo "$VERSION" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$'; then
    echo "ERROR: Version must be in X.Y.Z format (got: $VERSION)"
    exit 1
fi

TAG="v$VERSION"
GRADLE_PROPS="gradle.properties"

echo "=== GamePerf Desktop Release $TAG ==="
echo ""

# Step 1: Verify clean working tree
if [ -n "$(git status --porcelain)" ]; then
    echo "ERROR: Working tree is not clean. Commit or stash changes first."
    git status --short
    exit 1
fi

# Step 2: Update gradle.properties
echo "→ Updating $GRADLE_PROPS to appVersion=$VERSION"
sed -i '' "s/^appVersion=.*/appVersion=$VERSION/" "$GRADLE_PROPS"

# Step 3: Commit
echo "→ Creating commit"
git add "$GRADLE_PROPS"
git commit -m "chore: bump version to $TAG"

# Step 4: Tag
echo "→ Creating tag $TAG"
git tag "$TAG"

# Step 5: Push
echo "→ Pushing to origin"
git push origin main "$TAG"

echo ""
echo "✅ Tag $TAG pushed. CI will build linux/macos-arm64/windows JARs."
echo ""

# Step 6: Wait for CI
echo "→ Waiting for CI workflow to start..."
sleep 10
RUN_ID=$(gh run list --limit 1 --json databaseId --jq '.[0].databaseId')
echo "→ Watching CI run #$RUN_ID"
gh run watch "$RUN_ID" --exit-status || {
    echo "⚠️  CI failed. Check: gh run view $RUN_ID"
    echo "You can still build locally and upload manually."
}

# Step 7: Build local macos-x64
echo ""
echo "→ Building local macos-x64 uber JAR..."
./gradlew packageUberJarForCurrentOS
LOCAL_JAR=$(find build/compose/jars -name "*.jar" | head -1)
if [ -z "$LOCAL_JAR" ]; then
    echo "ERROR: No JAR found in build/compose/jars/"
    exit 1
fi
echo "→ Built: $LOCAL_JAR ($(stat -f%z "$LOCAL_JAR") bytes)"

# Step 8: Release body
echo ""
echo "→ Paste the CHANGELOG excerpt for the release body, then press Ctrl+D:"
BODY=$(cat)

# Step 9: Upload
echo ""
echo "→ Overwriting release body..."
echo "$BODY" > /tmp/gameperf-release-body.md
gh release edit "$TAG" --notes-file /tmp/gameperf-release-body.md
rm /tmp/gameperf-release-body.md

echo "→ Uploading local macos-x64 JAR..."
gh release upload "$TAG" "$LOCAL_JAR" --clobber

echo ""
echo "🎉 Release $TAG complete!"
echo "   → https://github.com/zeroz3r0/android-game-perf-tool-desktop/releases/tag/$TAG"
