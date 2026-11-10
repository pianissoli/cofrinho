# Cofrinho releases on GitHub

GitHub Actions builds, tests, signs, packages, and publishes the Android APK and
Linux x86_64 CLI on GitHub's Linux runner using the repository's Dockerfile. The
computer used to push a release tag does not need Java, Gradle, the Android SDK,
or a local build. Each release also publishes a self-contained Python installer
and checksum for replacing an existing per-user CLI installation.

The workflow is [Android release](../../.github/workflows/android-release.yml).
It runs when a tag such as `v0.1.0` is pushed. It publishes the APK, its SHA-256
checksum, the self-contained CLI archive, and its checksum as GitHub Release
assets after tests and verification pass. The assets `cofrinho-install_cli.py`,
`cli_install_archive.py`, and their checksums are the standalone installer;
keep both Python files together.

## Configure the existing signing key once

Keep the existing PKCS#12 signing key and its offline backup. The filename and
alias do not need to match the project name. Do not generate another key for
this pipeline. Future updates must use the same application ID and signing key.

Encode the existing `.p12` outside the checkout. Replace the source path below
with the real location; this command reads the key without building or signing
an APK:

```bash
umask 077
cofrinho_signing_dir="$HOME/.local/share/cofrinho-signing"
mkdir -p "$cofrinho_signing_dir"
base64 -w 0 /absolute/path/to/existing-release.p12 \
  > "$cofrinho_signing_dir/github-keystore.base64"
```

On GitHub, open the repository, then **Settings → Secrets and variables →
Actions → New repository secret**. Configure these four secrets:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Complete contents of `github-keystore.base64` |
| `ANDROID_STORE_PASSWORD` | Existing PKCS#12 keystore password |
| `ANDROID_KEY_ALIAS` | Actual private-key alias inside that file |
| `ANDROID_KEY_PASSWORD` | Private-key password; normally the same as the PKCS#12 store password |

Use the alias actually present in the keystore, even if it uses an earlier
project name. The workflow reconstructs the file on the runner and reads that
alias. Repository secrets belong to that GitHub repository; no personal access
token is required. The built-in `GITHUB_TOKEN` publishes the release.

Base64 does not encrypt the key. Keep the encoded file outside the repository
and remove it after saving the secret. Keep the original key and its secure
backup. No local `keystore.properties` file is needed for GitHub Actions.

If Actions is disabled, enable it under **Settings → Actions → General**.
The workflow requests `contents: write` to create release assets; organization
policy must permit that permission.

## Publish a version

For the normal next-patch release, start from a clean `master` checkout and run:

```bash
python3 scripts/bump.py
```

The script checks the remote and existing tags before editing version files.
It advances the patch version and Android `versionCode`, commits just those
files, pushes `master`, then creates and pushes the annotated tag that starts
this workflow. Python 3 and Git are the only local requirements. Local commits
already on `master` are included in the push; remote commits must already be
incorporated locally. No tags are force-updated or pushed in bulk.

If a push fails after the version commit, finish that release with the recovery
commands printed by the script. Do not run a new bump to retry an incomplete
release. The version commit and any local tag are preserved for recovery.

For a manual release or a minor/major version change, use the steps below.

In `apps/android/build.gradle.kts`, set:

- `versionName` to the release version, for example `0.1.0`.
- `versionCode` to a positive integer higher than the previous published build.
  The first build uses `1`; subsequent releases must increase it.

Commit and push the workflow, documentation, and any version changes. For the
initial release setup:

```bash
git add .github/workflows/android-release.yml README.md docs/release/
git commit -m "ci: publish signed Android APKs on GitHub"
git push origin HEAD
```

If a version was changed, include `apps/android/build.gradle.kts` in that commit.
Then push the matching version tag:

```bash
git tag -a v0.1.0 -m "Cofrinho 0.1.0"
git push origin v0.1.0
```

Use `vMAJOR.MINOR.PATCH`. The workflow checks the APK's actual `versionName`
against the tag and fails if they differ. Published release tags must remain
unchanged. Tests or signing failures stop publication; inspect the failed step
in **Actions → Android release**, fix it, and publish a new version/tag. A
transient failure before publication can be retried using **Re-run failed jobs**.
An existing release is not overwritten by a rerun.

## Download and install

Wait for the **Android release** workflow to succeed. Open **Releases**, select
the version, and download `cofrinho-v0.1.0.apk` under **Assets**. The `.sha256`
file provides a download checksum.

Transfer the APK to the phone, open it, and allow installation from the app
opening the file if Android asks. The current app requires Android 16 (API 36)
or newer. Later APKs install as updates when the application ID and signing key
match and `versionCode` increases. Earlier development installations with a
different application ID require exporting and importing their ledger.

### Install the Linux CLI

On Linux x86_64, download the CLI archive and its checksum, plus
`cofrinho-install_cli.py`, `cli_install_archive.py`, and their checksums. Python 3.11 or newer is the only
host requirement; Java, Gradle, Docker, and root access are not needed. Verify
the files before execution:

```bash
sha256sum --check cofrinho-install_cli.py.sha256
sha256sum --check cli_install_archive.py.sha256
sha256sum --check cofrinho-v0.2.0-linux-x86_64.tar.gz.sha256
python3 cofrinho-install_cli.py \
  "$HOME/Downloads/cofrinho-v0.2.0-linux-x86_64.tar.gz"
```

The default install tree is `~/.local/opt/cofrinho/` and the command is the
symlink `~/.local/bin/cofrinho`. An update stages and validates the complete
distribution, tests `bin/cofrinho --help` with an invalid `JAVA_HOME` (so the
bundled runtime is exercised), switches the command, and removes only the
validated previous program tree. It never accesses the ledger at
`~/.local/share/cofrinho/`, SQLite `-wal`/`-shm`/journal/lock companions,
backups, exports, or transfer state. This preserves bytes even when an older
database is intentionally incompatible with the current schema.

Preview a change without extraction or execution:

```bash
python3 cofrinho-install_cli.py \
  "$HOME/Downloads/cofrinho-v0.2.0-linux-x86_64.tar.gz" --dry-run
```

If a previous manual release remains in a version-named directory, retire only
that exact validated path after the new installation succeeds:

```bash
python3 cofrinho-install_cli.py \
  "$HOME/Downloads/cofrinho-v0.2.0-linux-x86_64.tar.gz" \
  --retire-install "$HOME/.local/opt/cofrinho-v0.1.2"
```

Use `--install-dir PATH` and `--bin-dir PATH` for a different per-user layout.
The installer does not edit shell startup files; add `~/.local/bin` to `PATH`
when it prints that suggestion. If activation or smoke testing fails, the
previous distribution and command link are restored. If a cleanup permission
error remains, rerun the same command; recovery metadata names the exact old
paths still pending.

## What the workflow does

1. Checks out the version tag and validates its format.
2. Builds `cofrinho-build` and runs the repository's `check` task.
3. Packages the Linux x86_64 CLI with its bundled JRE.
4. Runs CLI operations in an Ubuntu container with no system Java and an invalid
   `JAVA_HOME`, including SQLite and export/import checks, then drives the
   default TUI through a real 80x24 pseudo-terminal.
5. Decodes the secret keystore outside the checkout and mounts it read-only.
6. Builds the release APK using Android Gradle Plugin's runtime signing options.
7. Deletes the temporary keystore even if signing fails.
8. Checks that the APK version matches the tag and verifies its signature with
   `apksigner`, printing the public certificate fingerprint for review.
9. Publishes the signed APK, CLI archive, installer, and all four checksums to GitHub Releases.

The keystore is never copied into the Docker image. Signing passwords enter
only the signing container through environment variables; the workflow does
not write them into repository files. Signing still happens on GitHub's runner,
so the repository's release maintainers and workflow code must remain trusted.
