# Android releases

Pushing a version tag runs [Android CI](../.github/workflows/android.yml), builds a signed ARM64 release APK and publishes it to [GitHub Releases](https://github.com/VitalyArt/byd-keyless/releases). Unit tests, debug lint/build and release lint/build must all pass before publication. Branch pushes and pull requests only run the existing checks.

## One-time signing setup

Use a dedicated release keystore and keep an encrypted backup of it and its passwords. Every release must use the same key so Android can install updates over earlier releases. GitHub Secrets cannot be used to download a backup later.

If you already have a release key for this application, reuse it. Otherwise generate one outside the repository (the command prompts for a password and certificate details):

```bash
mkdir -p "$HOME/.android"
keytool -genkeypair -v -keystore "$HOME/.android/byd-keyless-release.jks" \
  -storetype JKS -alias byd-keyless -keyalg RSA -keysize 4096 -validity 10000
```

In the repository's **Settings → Secrets and variables → Actions**, add these repository secrets:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64-encoded contents of the release keystore |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Signing key alias, e.g. `byd-keyless` |
| `ANDROID_KEY_PASSWORD` | Signing key password |

Alternatively, use the authenticated GitHub CLI. These commands upload the keystore directly and prompt for the remaining values without putting passwords in shell history:

```bash
base64 < "$HOME/.android/byd-keyless-release.jks" | gh secret set ANDROID_KEYSTORE_BASE64 --repo VitalyArt/byd-keyless
gh secret set ANDROID_KEYSTORE_PASSWORD --repo VitalyArt/byd-keyless
gh secret set ANDROID_KEY_ALIAS --repo VitalyArt/byd-keyless
gh secret set ANDROID_KEY_PASSWORD --repo VitalyArt/byd-keyless
```

No personal access token secret is needed: publication uses the job's automatic `GITHUB_TOKEN` with `contents: write`. Build jobs keep read-only repository permissions. Signing credentials are only passed to configuration validation and APK signing; the temporary keystore is removed after signing. Missing secrets fail the release without publishing an unsigned APK.

## Publish a version

First commit and push the workflow and application changes. Tag the intended commit and push that tag:

```bash
git tag -a v0.1.0 -m "BYD Keyless v0.1.0"
git push origin v0.1.0
```

Supported tags are `vMAJOR.MINOR.PATCH` (the `v` is optional), optionally followed by a prerelease suffix, such as `v0.2.0-rc1` or `v0.2.0-beta.1`. Tags with a suffix create a GitHub prerelease and do not become Latest. Other tag formats fail validation.

The APK's `versionName` comes from the tag without its leading `v`. Its `versionCode` is the Android CI workflow's `github.run_number + 1`; this increases across new runs and stays above the original development build's code of 1. Keep this workflow file's identity when changing the pipeline, and publish versions in order: if the workflow is replaced or its run numbering resets, adjust the code calculation to remain above all published codes. Re-running the same workflow run keeps its code.

The release contains:

- `BYDKeyless-v0.1.0-arm64-v8a.apk` — signed, minified release build for Android 8.0+ on ARM64.
- `SHA256SUMS.txt` — SHA-256 checksum for the APK.

The R8 `mapping.txt` file is stored separately in the tag run's GitHub Actions artifact `android-mapping-v<VERSION>` for 90 days. It is used to interpret crash stack traces from that exact build and is not attached to the GitHub Release. Download and archive the artifact before it expires if you need longer-term crash diagnostics.

GitHub generates release notes from repository changes. Re-running a failed tag run updates assets on an existing release instead of creating a duplicate; existing release notes are preserved. Keep published tags on their original commits and use a new version tag for code changes.

The first installation of a release APK over a locally signed debug build requires uninstalling the debug build because the signing keys differ. Uninstalling clears the app's local session and settings. Subsequent release APKs signed with the same release key can be installed as updates.

## Local release build

Version overrides are optional and do not change the default development version:

```bash
./gradlew -PappVersionName=0.1.0 -PappVersionCode=2 :app:lintRelease :app:assembleRelease
```

This produces `app/build/outputs/apk/release/app-release-unsigned.apk`. CI signs and verifies it with Android SDK Build-Tools 34.0.0 before uploading. A local unsigned APK cannot be installed as-is. Tests do not connect to a vehicle; real-device and vehicle validation remain separate.
