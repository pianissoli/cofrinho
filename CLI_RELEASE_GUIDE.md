# Add CLI packaging to the existing release pipeline

This guide records the implementation in the current
[Android release workflow](.github/workflows/android-release.yml). It reuses the
existing version-tag trigger, Docker image, checks, signing steps, and GitHub
Release publisher.

The result should be one GitHub Release containing:

```text
cofrinho-v0.1.1.apk
cofrinho-v0.1.1.apk.sha256
cofrinho-v0.1.1-linux-x86_64.tar.gz
cofrinho-v0.1.1-linux-x86_64.tar.gz.sha256
```

The CLI archive includes the launcher, application and dependency JARs, and a
Java runtime. Users need neither Java nor Docker installed to run it. The first
CLI target remains Linux x86_64 with glibc; the example smoke test uses Ubuntu
22.04, matching the current Docker build image's base distribution.

## Bundled-runtime fix

The existing [packageLinuxX64 task](apps/cli/build.gradle.kts) runs `installDist`,
copies the distribution to `apps/cli/build/package/cofrinho-linux-x86_64/`, and
uses `jlink` to create a runtime under that directory's `jre/`.

However, Gradle's generated `bin/cofrinho` launcher selects Java through
`JAVA_HOME` or the system `PATH`. It does not automatically discover the sibling
`jre/`. Merely including that directory does not make the CLI self-contained:

- A computer without Java can fail to start the packaged CLI.
- An existing `JAVA_HOME` can select an incompatible runtime.
- Testing only in `cofrinho-build` can hide the gap because that image already
  includes a full JDK.

The task now fixes this in the CLI packaging owned by
`apps/cli/build.gradle.kts`. It patches the generated Unix launcher so that,
after resolving its application directory and before selecting Java, it sets
`JAVA_HOME` to the package's `jre/`. Override an ambient `JAVA_HOME`, and fail
clearly if the bundled `jre/bin/java` is missing rather than silently falling
back to system Java. Preserve argument forwarding, executable permissions,
paths containing spaces, and the launcher's existing symlink resolution.

The installed archive must retain this structure, including runtime libraries
and license notices produced by `jlink`:

```text
cofrinho-linux-x86_64/
  bin/cofrinho
  lib/*.jar
  jre/bin/java
  jre/lib/...
  jre/legal/...
```

The task also validates Linux **and** x86_64, keeps the required runtime modules,
and verifies them through actual CLI operations in the clean-container test below.

## Does this affect the current Android release?

**No. The CLI launcher gap does not affect the APK already built or released.**
Android uses its own runtime and does not execute `bin/cofrinho` or use this
bundled desktop `jre/`. The current workflow does not invoke `packageLinuxX64`.
Its existing `check` task does run CLI tests, but those tests use the build JDK
and do not prove that the distributed launcher can find its bundled runtime.

The integrated CLI steps intentionally stop the
shared release before publication, including the APK upload. That is a release
gate, not a new dependency of the Android app on the desktop runtime. APK
signing and verification must continue using the existing key and steps.

## Add CLI build and archive steps

The workflow runs the following steps **after `Run checks` and
before `Sign release APK`** in the existing job. The Docker image has already
been built, so do not build another image or repeat the root `check` task.
These steps need no Android signing secrets.

```yaml
- name: Package Linux x86_64 CLI
  run: |
    docker run --rm \
      --user "$(id -u):$(id -g)" \
      --volume "$PWD:/workspace" \
      cofrinho-build :apps:cli:packageLinuxX64

    mkdir -p "$RUNNER_TEMP/cofrinho-release"
    cli_archive="cofrinho-$GITHUB_REF_NAME-linux-x86_64.tar.gz"
    tar -C apps/cli/build/package \
      -czf "$RUNNER_TEMP/cofrinho-release/$cli_archive" \
      cofrinho-linux-x86_64
    cd "$RUNNER_TEMP/cofrinho-release"
    sha256sum "$cli_archive" > "$cli_archive.sha256"

- name: Smoke test CLI archive without system Java
  run: |
    mkdir -p "$RUNNER_TEMP/cofrinho-cli-smoke/extracted package"
    tar -xzf "$RUNNER_TEMP/cofrinho-release/cofrinho-$GITHUB_REF_NAME-linux-x86_64.tar.gz" \
      -C "$RUNNER_TEMP/cofrinho-cli-smoke/extracted package"

    docker run --rm --network none \
      --user "$(id -u):$(id -g)" \
      --env JAVA_HOME=/missing-system-java \
      --volume "$RUNNER_TEMP/cofrinho-cli-smoke:/smoke" \
      --entrypoint /bin/bash \
      ubuntu:22.04 -ceu '
        if command -v java >/dev/null 2>&1; then
          printf "Expected a smoke-test container without system Java\n" >&2
          exit 1
        fi
        cli="/smoke/extracted package/cofrinho-linux-x86_64/bin/cofrinho"
        "$cli"
        "$cli" --db /smoke/source.sqlite setup --currency BRL
        "$cli" --db /smoke/source.sqlite income add --name Salary --amount 3000 --from 2026-11
        "$cli" --db /smoke/source.sqlite expense add --amount 10 --date 2026-11-02
        "$cli" --db /smoke/source.sqlite expense plan --amount 100 --expected-date 2026-11-20
        "$cli" --db /smoke/source.sqlite budget show --month 2026-11 --json > /smoke/before.json
        "$cli" --db /smoke/source.sqlite transfer export --file /smoke/outbound.cofrinho
        "$cli" --db /smoke/target.sqlite setup --currency BRL
        "$cli" --db /smoke/target.sqlite transfer import --file /smoke/outbound.cofrinho
        "$cli" --db /smoke/target.sqlite budget show --month 2026-11 --json > /smoke/imported.json
        "$cli" --db /smoke/target.sqlite transfer export --file /smoke/returned.cofrinho
        "$cli" --db /smoke/source.sqlite transfer import --file /smoke/returned.cofrinho
        "$cli" --db /smoke/source.sqlite budget show --month 2026-11 --json > /smoke/after.json
      '

    jq --exit-status \
      '.budget == "3000.00" and .spent == "10.00" and .reserved == "100.00" and .available == "2890.00"' \
      "$RUNNER_TEMP/cofrinho-cli-smoke/before.json"
    cmp "$RUNNER_TEMP/cofrinho-cli-smoke/before.json" "$RUNNER_TEMP/cofrinho-cli-smoke/imported.json"
    cmp "$RUNNER_TEMP/cofrinho-cli-smoke/before.json" "$RUNNER_TEMP/cofrinho-cli-smoke/after.json"
```

Match the existing `steps:` indentation when inserting the YAML. The workflow
already selects Bash, which fails a step when any of these commands fails.

The smoke test extracts the actual archive to a path containing a space. It
checks startup with no system Java and a deliberately invalid `JAVA_HOME`,
SQLite access, recurring income, recorded and planned expenses, exact budget
totals, and a complete export/import return trip. Explicit dates keep the test
independent of the runner's clock. All financial files are temporary fixtures
under `RUNNER_TEMP`, outside the checkout and the published archive.

If the bundled runtime lacks a necessary module or native dependency, fix or
document that dependency rather than testing only in the JDK-equipped builder.
Test any additional Linux distributions before claiming support for them.

## Extend the existing publisher

Keep `Verify and prepare release assets`, including its APK version/tag check,
signature verification, and APK checksum. That step already uses `mkdir -p`
for the release directory, so it preserves the CLI archive created earlier.
Keep the signing-key cleanup step and its `always()` condition.

The existing `Publish GitHub Release` step now uses the following expanded asset
list:

```yaml
- name: Publish GitHub Release
  env:
    GH_TOKEN: ${{ github.token }}
  run: |
    gh release create "$GITHUB_REF_NAME" \
      "$RUNNER_TEMP/cofrinho-release/cofrinho-$GITHUB_REF_NAME.apk" \
      "$RUNNER_TEMP/cofrinho-release/cofrinho-$GITHUB_REF_NAME.apk.sha256" \
      "$RUNNER_TEMP/cofrinho-release/cofrinho-$GITHUB_REF_NAME-linux-x86_64.tar.gz" \
      "$RUNNER_TEMP/cofrinho-release/cofrinho-$GITHUB_REF_NAME-linux-x86_64.tar.gz.sha256" \
      --repo "$GITHUB_REPOSITORY" \
      --verify-tag \
      --title "Cofrinho $GITHUB_REF_NAME" \
      --generate-notes
```

Use one publisher for both artifacts. Do not add a second workflow that also
calls `gh release create` for the same tag. Preserve normal success conditions
for packaging, smoke tests, APK verification, and publication; do not use
`continue-on-error` or `if: always()` on those steps.

## Release and installation checks

1. Review the CLI launcher fix and let the workflow test it before publishing.
2. Keep the root project version, Android `versionName`, and release tag
   aligned. Increase Android `versionCode` for each published update.
3. Commit the launcher and workflow changes. Use a new tag such as `v0.1.1`;
   do not move or recreate an already published `v0.1.0` tag.
4. Let GitHub build, package, and test both applications. No local APK build or
   signing is required, and no new signing secrets are needed for the CLI.
5. Confirm that the GitHub Release contains all four files listed above.
6. Download the CLI archive and checksum into the same directory and verify:

   ```bash
   sha256sum --check cofrinho-v0.1.1-linux-x86_64.tar.gz.sha256
   tar -xzf cofrinho-v0.1.1-linux-x86_64.tar.gz
   ./cofrinho-linux-x86_64/bin/cofrinho
   ```

Users can move the extracted directory to an installation location and add
its `bin` directory to `PATH`. Preserve the full directory layout. Application
updates replace the installed package; financial records remain outside it at
`~/.local/share/cofrinho/cofrinho.sqlite`, or the path selected with `--db`.

The checksum detects a damaged or changed download; it is not a publisher
signature. The CLI does not use the Android keystore. Keep Android signing
secrets confined to the existing APK signing step.
