#!/usr/bin/env bash
#
# Cut a signed release of the malteish Paperize fork.
#
# Does the whole mechanical dance in one shot:
#   1. bump versionCode (+1) and versionName in app/build.gradle.kts
#   2. commit "build: release vX.Y.Z"
#   3. create annotated tag vX.Y.Z
#   4. push the branch and the tag (the tag triggers the release workflow)
#   5. with gh installed, watch the pipeline and print the published release URL
#
# The tag push runs .github/workflows/android-release.yml: test -> signed
# assembleRelease -> paperize-vX.Y.Z.apk -> GitHub Release (marked Latest),
# which Obtainium then installs. Obtainium pins the signing cert, so the
# versionCode MUST increase every release or the update won't install — this
# script guarantees that.
#
# Usage:
#   scripts/release.sh <X.Y.Z | major | minor | patch> [--check] [--no-watch] [--dry-run]
#
# Examples:
#   scripts/release.sh patch        # 4.1.1 -> 4.1.2, versionCode +1
#   scripts/release.sh minor        # 4.1.1 -> 4.2.0
#   scripts/release.sh 5.0.0        # explicit version
#   scripts/release.sh patch --check    # run unit tests before tagging
#   scripts/release.sh patch --dry-run  # show the plan, change nothing
#
# Preconditions: run on the release branch (default malteish-release, override
# with RELEASE_BRANCH=...) with a clean working tree — commit your feature
# first; this script only makes the version-bump commit.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

GRADLE_FILE="app/build.gradle.kts"
RELEASE_BRANCH="${RELEASE_BRANCH:-malteish-release}"

die() { echo "release: $*" >&2; exit 1; }
usage() { sed -n '2,30p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

# ---- parse args ----
BUMP="" DRY_RUN=0 WATCH=1 CHECK=0
for a in "$@"; do
  case "$a" in
    --dry-run) DRY_RUN=1 ;;
    --no-watch) WATCH=0 ;;
    --check) CHECK=1 ;;
    -h|--help) usage; exit 0 ;;
    -*) die "unknown flag: $a" ;;
    *) [ -z "$BUMP" ] || die "unexpected extra argument: $a"; BUMP="$a" ;;
  esac
done
[ -n "$BUMP" ] || { usage; exit 2; }

# ---- preconditions ----
command -v git >/dev/null || die "git not found"
[ -f "$GRADLE_FILE" ] || die "$GRADLE_FILE not found — run from inside the Paperize repo"

branch="$(git rev-parse --abbrev-ref HEAD)"
[ "$branch" = "$RELEASE_BRANCH" ] || \
  die "on branch '$branch' but releases go from '$RELEASE_BRANCH' (override with RELEASE_BRANCH=...)"
[ -z "$(git status --porcelain)" ] || \
  die "working tree not clean — commit or stash your changes first (this script only makes the version-bump commit)"

# ---- read current version ----
cur_code="$(grep -oE 'versionCode[[:space:]]*=[[:space:]]*[0-9]+' "$GRADLE_FILE" | grep -oE '[0-9]+$')" || true
cur_name="$(grep -oE 'versionName[[:space:]]*=[[:space:]]*"[^"]*"' "$GRADLE_FILE" | sed -E 's/.*"([^"]*)".*/\1/')" || true
[ -n "$cur_code" ] || die "could not read versionCode from $GRADLE_FILE"
[ -n "$cur_name" ] || die "could not read versionName from $GRADLE_FILE"

# ---- compute new versionName ----
case "$BUMP" in
  major|minor|patch)
    IFS=. read -r MA MI PA <<<"$cur_name"
    [[ "$MA" =~ ^[0-9]+$ && "$MI" =~ ^[0-9]+$ && "$PA" =~ ^[0-9]+$ ]] || \
      die "current versionName '$cur_name' is not X.Y.Z; pass an explicit version instead"
    case "$BUMP" in
      major) MA=$((MA + 1)); MI=0; PA=0 ;;
      minor) MI=$((MI + 1)); PA=0 ;;
      patch) PA=$((PA + 1)) ;;
    esac
    new_name="$MA.$MI.$PA" ;;
  *)
    [[ "$BUMP" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || \
      die "version must be X.Y.Z or one of: major | minor | patch"
    new_name="$BUMP" ;;
esac

new_code=$((cur_code + 1))
tag="v$new_name"

# ---- sanity: no duplicate version/tag ----
[ "$new_name" != "$cur_name" ] || die "versionName is already $new_name — nothing to release"
git rev-parse -q --verify "refs/tags/$tag" >/dev/null && die "tag $tag already exists locally"
if git ls-remote --exit-code --tags origin "refs/tags/$tag" >/dev/null 2>&1; then
  die "tag $tag already exists on origin"
fi

echo "Release plan"
echo "  branch      : $branch"
echo "  versionCode : $cur_code -> $new_code"
echo "  versionName : $cur_name -> $new_name"
echo "  tag         : $tag"
echo

if [ "$DRY_RUN" = 1 ]; then echo "dry run — nothing changed."; exit 0; fi

# ---- optional local test gate ----
if [ "$CHECK" = 1 ]; then
  echo "Running unit tests before tagging…"
  ./gradlew testDebugUnitTest
  echo
fi

# ---- edit build.gradle.kts (require exactly one match each) ----
[ "$(grep -cE 'versionCode[[:space:]]*=[[:space:]]*[0-9]+' "$GRADLE_FILE")" = 1 ] || \
  die "expected exactly one versionCode line in $GRADLE_FILE"
[ "$(grep -cE 'versionName[[:space:]]*=[[:space:]]*"[^"]*"' "$GRADLE_FILE")" = 1 ] || \
  die "expected exactly one versionName line in $GRADLE_FILE"

sed -i -E "s/(versionCode[[:space:]]*=[[:space:]]*)[0-9]+/\1$new_code/" "$GRADLE_FILE"
sed -i -E "s/(versionName[[:space:]]*=[[:space:]]*)\"[^\"]*\"/\1\"$new_name\"/" "$GRADLE_FILE"

grep -qE "versionCode[[:space:]]*=[[:space:]]*$new_code([^0-9]|$)" "$GRADLE_FILE" || die "versionCode edit failed"
grep -qE "versionName[[:space:]]*=[[:space:]]*\"$new_name\"" "$GRADLE_FILE" || die "versionName edit failed"

# ---- commit, tag, push ----
git add "$GRADLE_FILE"
git commit -q -m "build: release $tag

Bump versionCode $cur_code -> $new_code, versionName $cur_name -> $new_name.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
git tag -a "$tag" -m "$tag"

echo "Committed and tagged $tag. Pushing…"
git push origin "$branch"
git push origin "$tag"
echo "Pushed $branch and $tag."
echo

# ---- watch the pipeline and report ----
if [ "$WATCH" = 1 ] && command -v gh >/dev/null 2>&1; then
  slug="$(git remote get-url origin \
    | sed -E -e 's#^git@[^:]+:##' -e 's#^https?://[^/]+/##' -e 's#\.git$##')"
  echo "Waiting for the release pipeline on $slug (tag $tag)…"
  run_id=""
  for _ in $(seq 1 24); do
    run_id="$(gh run list -R "$slug" --limit 15 --json databaseId,headBranch,event \
      --jq "[.[] | select(.event==\"push\" and .headBranch==\"$tag\")][0].databaseId" 2>/dev/null || true)"
    [ -n "$run_id" ] && [ "$run_id" != "null" ] && break
    sleep 5
  done
  if [ -n "$run_id" ] && [ "$run_id" != "null" ]; then
    gh run watch "$run_id" -R "$slug" --exit-status --interval 15 \
      || die "release pipeline failed — inspect the run above (the tag is pushed; fix and re-tag)"
    echo
    gh release view "$tag" -R "$slug" --json url,assets \
      --jq '"Published: \(.url)\nAPK:       \(.assets[].name)"' 2>/dev/null \
      || echo "Pipeline finished; check the Releases page on $slug."
    echo
    echo "Obtainium will offer $tag on its next check."
  else
    echo "Couldn't locate the pipeline run yet — check GitHub Actions on $slug."
  fi
else
  echo "Not watching CI (gh missing or --no-watch). Track it in GitHub Actions;"
  echo "the release publishes automatically when the $tag pipeline passes."
fi

echo "Done."
