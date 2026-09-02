# Publication checks

This document records the original public-source preparation checks. For the current automatic APK release workflow, see [Release setup and publishing](RELEASING.md). Native binaries are retained and no project `LICENSE` is added; see [Third-party components](../THIRD_PARTY.md).

## Before uploading

1. Review both staged and unstaged changes, including new source files. Preparation preserves the existing index, so staging only the new documentation is not sufficient to capture the current app. Review the complete final snapshot before making a publication commit.
2. Keep `local.properties`, `.env` files, signing credentials, device/session dumps and build outputs out of Git. `.gitignore` does not remove files already in the index or history.
3. Scan the final working files, staged contents and all history for secrets. Never upload scanner reports containing unredacted findings. If a real secret is found, stop, revoke/rotate it and agree on a history-cleanup procedure before publishing.
4. Review commit author names/emails and historical machine paths. Existing history is intentionally preserved: removing paths from the current README/build settings does not remove them from earlier commits. Do not rewrite that history without explicit approval.
5. Confirm applicable redistribution permissions for the bundled native libraries. Their continued inclusion is not a license verification.
6. Run the checks below using JDK 17 and the SDK packages listed in the [README](../README.md), then review the results. Real-vehicle validation remains a separate manual activity.

```bash
git status --short
git diff --check
git diff --cached --check
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

For a portability check, copy only the intended publication files into a new directory without `.git`, `.idea`, `.gradle`, build outputs or `local.properties`. Include untracked source/documentation files intended for publication; do not use an archive of the old commit alone. Select Java and the SDK through environment variables. Use a separate Gradle user home without personal properties or init scripts; dependency/download caches may be reused.

## Recorded checks and known warnings

Latest follow-up check on 2026-09-02: 35 unit tests passed (including three new manifest/shortcut regression checks), the debug APK was built, and lint reported 0 errors and 26 warnings using the existing local dependency metadata. Packaged APK permissions were inspected as well. The earlier clean-copy results below describe the original publication-preparation snapshot, not this later revision.

Initial publication-preparation checks on 2026-09-02 (before subsequent feature changes):

- The pre-preparation check passed 35 unit tests and built the debug APK; lint reported 0 errors and 27 warnings.
- The final application/build configuration was checked in a separate source copy without `local.properties`, IDE configuration or pre-existing build outputs. A separate Gradle user home reused only dependency/download caches, not personal properties or init scripts. With JDK 17 and the SDK selected through environment variables, all 54 tasks executed successfully using `--offline --no-daemon --continue`: 35 tests passed, the debug APK was built, and lint reported 0 errors and 12 warnings. Offline lint did not reproduce the online dependency-update notices; the lower count is not a set of code fixes.
- Gitleaks 8.30.1 reported no findings in the publication-eligible working files, an exported index and all reachable Git history (one commit). This is a pattern-based check, not proof that compiled third-party binaries contain no sensitive data. Local ignored settings and build outputs are not part of the publication snapshot.
- The Gradle Wrapper JAR checksum matched the official Gradle 8.6 checksum. Workflow YAML, pinned action inputs, documentation links, ignore rules and both staged/unstaged whitespace checks were validated. Existing application files, native binaries, index entries and HEAD were preserved.

These are point-in-time local results, not a claim of vehicle safety or a completed GitHub Actions run. The Ubuntu workflow still needs its first run after the repository is published.

Remaining warning categories include newer dependency versions, attributes ignored on older Android versions, ARM64-only ABI support, unused resources and launcher-icon suggestions. No blanket suppressions or lint baseline are added by publication preparation.

The existing AGP 8.3.2 / compile SDK 36 combination also retains `android.suppressUnsupportedCompileSdk=36`; SDK XML/tooling compatibility warnings can occur. Dependency/toolchain upgrades and application behavior fixes are intentionally outside this preparation. A successful build does not imply that this old plugin officially supports SDK 36.

GitHub Actions runs the same unit-test/lint/debug-build tasks on Ubuntu and retains reports for 14 days. Version-tag pushes additionally build, sign and publish an APK as described in [Releasing](RELEASING.md). CI does not use a BYD account or run connected-device tests. Build jobs use read-only repository permissions; only the release publication job can write releases. Actions are pinned to commits; review upstream changes before updating those pins.
