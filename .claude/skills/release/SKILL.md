---
name: release
description: Cut a signed release of the malteish Paperize fork so Obtainium can update — bump versionCode/versionName, commit, tag vX.Y.Z, push, and watch the release pipeline. Use when the user asks to release, ship, publish, cut a version, or "release patch/minor/major".
---

# Cut a Paperize release

Releasing is fully handled by `scripts/release.sh`. Do not hand-edit the version or craft the tag yourself — run the script; it enforces the invariants Obtainium needs (higher `versionCode`, correct branch, `vX.Y.Z` tag).

## Steps

1. **Make sure the feature work is already committed.** The script only creates the version-bump commit and requires a clean working tree. If there are uncommitted changes that belong in the release, commit them first (on `malteish-release`); if they don't, stash them.

2. **Pick the bump.** Map the user's intent to a bump arg:
   - bug fix / small tweak → `patch`
   - new feature → `minor`
   - breaking / big change → `major`
   - user named a version → pass it verbatim as `X.Y.Z`
   If it's ambiguous, default to `patch` and say so.

3. **Preview, then run.** From the repo root:
   ```bash
   scripts/release.sh <patch|minor|major|X.Y.Z> --dry-run   # show the plan
   scripts/release.sh <patch|minor|major|X.Y.Z>             # do it
   ```
   Add `--check` to run unit tests locally before tagging when the change is risky.

4. **Report the result.** The script watches CI and prints the published release URL and APK name. Relay those, and confirm Obtainium will pick it up. If the pipeline fails, surface the failing run — the tag is already pushed, so the fix is to correct the problem and re-tag a new version.

## Notes

- Runs only from `malteish-release` (override with `RELEASE_BRANCH=...`).
- Needs `gh` for the CI watch/report; without it the release still happens, but the user must check GitHub Actions manually.
- Never lower `versionCode` or reuse a tag — the script blocks both, and doing so would break Obtainium updates.
