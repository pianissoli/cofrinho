#!/usr/bin/env python3
"""Publish the next patch release. Usage: python3 scripts/bump.py."""

import argparse
from dataclasses import dataclass
from pathlib import Path
import re
import subprocess
import sys


_ROOT_BUILD: str = "build.gradle.kts"
_ANDROID_BUILD: str = "apps/android/build.gradle.kts"
_VERSION_SHAPE: str = r'"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)"'
_MAX_ANDROID_CODE: int = 2_147_483_647


@dataclass(frozen=True)
class _ReleaseWorkspace:
    root: Path

    def git(self, *arguments: str) -> str:
        """Run Git in this checkout. Example: workspace.git("status", "--short")."""
        completed = subprocess.run(
            ["git", *arguments], cwd=self.root, check=True, capture_output=True, text=True
        )
        return completed.stdout.strip()

    def read(self, relative_path: str) -> str:
        """Read a version source. Example: workspace.read("build.gradle.kts")."""
        return (self.root / relative_path).read_text(encoding="utf-8")

    def write(self, relative_path: str, contents: str) -> None:
        """Save a validated version source. Example: workspace.write(path, contents)."""
        (self.root / relative_path).write_text(contents, encoding="utf-8")


@dataclass(frozen=True)
class _PatchUpdate:
    version: str
    android_code: int
    root_contents: str
    android_contents: str


def _assignment(contents: str, field: str, shape: str) -> re.Match[str]:
    pattern = rf"(?m)^([ \t]*{re.escape(field)}[ \t]*=[ \t]*)({shape})([ \t]*)$"
    matches = list(re.finditer(pattern, contents))
    if len(matches) != 1:
        candidates = [line for line in contents.splitlines() if re.match(rf"\s*{field}\s*=", line)]
        raise ValueError(f"Assignment {field!r} has values {candidates!r}; expected exactly one value matching {shape!r}")
    return matches[0]


def _replace_assignment(contents: str, match: re.Match[str], replacement: str) -> str:
    return contents[: match.start(2)] + replacement + contents[match.end(2) :]


def _next_version(project_version: str, android_version: str) -> str:
    if project_version != android_version:
        raise ValueError(f"Versions {project_version!r} and {android_version!r} differ; expected identical project and Android versions")
    major, minor, patch = map(int, project_version.strip('"').split("."))
    return f"{major}.{minor}.{patch + 1}"


def _next_android_code(current: int) -> int:
    if not 1 <= current < _MAX_ANDROID_CODE:
        raise ValueError(f"Android versionCode {current!r} cannot advance; expected an integer from 1 to {_MAX_ANDROID_CODE - 1}")
    return current + 1


def _plan_patch(workspace: _ReleaseWorkspace) -> _PatchUpdate:
    root_contents = workspace.read(_ROOT_BUILD)
    android_contents = workspace.read(_ANDROID_BUILD)
    root_version = _assignment(root_contents, "version", _VERSION_SHAPE)
    android_version = _assignment(android_contents, "versionName", _VERSION_SHAPE)
    android_code = _assignment(android_contents, "versionCode", r"(?:0|[1-9][0-9]*)")
    version = _next_version(root_version.group(2), android_version.group(2))
    code = _next_android_code(int(android_code.group(2)))
    updated_android = _replace_assignment(android_contents, android_version, f'"{version}"')
    updated_code = _assignment(updated_android, "versionCode", r"(?:0|[1-9][0-9]*)")
    return _PatchUpdate(
        version, code,
        _replace_assignment(root_contents, root_version, f'"{version}"'),
        _replace_assignment(updated_android, updated_code, str(code)),
    )


def _require_clean_master(workspace: _ReleaseWorkspace) -> None:
    status = workspace.git("status", "--porcelain")
    if status:
        raise ValueError(f"Working tree contains {status!r}; expected a clean checkout before releasing")
    branch = workspace.git("branch", "--show-current")
    if branch != "master":
        raise ValueError(f"Current branch is {branch!r}; expected 'master'")


def _check_remote_release(workspace: _ReleaseWorkspace, release_tag: str) -> None:
    # Fetch before editing so an unavailable remote cannot leave a partial bump.
    workspace.git("fetch", "origin", "master", "--tags")
    remote_only, _ = workspace.git("rev-list", "--left-right", "--count", "origin/master...HEAD").split()
    if remote_only != "0":
        raise ValueError(f"origin/master has {remote_only!r} missing local commits; expected master to include the remote history")
    existing_tag = workspace.git("tag", "--list", release_tag)
    if existing_tag:
        raise ValueError(f"Release tag {existing_tag!r} already exists; expected a new version tag")


def _commit_patch(workspace: _ReleaseWorkspace, update: _PatchUpdate) -> None:
    workspace.write(_ROOT_BUILD, update.root_contents)
    workspace.write(_ANDROID_BUILD, update.android_contents)
    workspace.git("diff", "--check", "--", _ROOT_BUILD, _ANDROID_BUILD)
    workspace.git("add", "--", _ROOT_BUILD, _ANDROID_BUILD)
    workspace.git("commit", "-m", f"chore: bump version to {update.version}")


def _publish_patch(workspace: _ReleaseWorkspace, version: str) -> None:
    release_tag = f"v{version}"
    try:
        workspace.git("push", "origin", "HEAD:refs/heads/master")
        workspace.git("tag", "-a", release_tag, "-m", f"Cofrinho {version}")
        workspace.git("push", "origin", f"refs/tags/{release_tag}:refs/tags/{release_tag}")
    except subprocess.CalledProcessError:
        print(f"Version commit for {version} is preserved. Do not run another bump yet.", file=sys.stderr)
        print("Finish this release with: git push origin HEAD:refs/heads/master", file=sys.stderr)
        print(f"If {release_tag} does not exist locally: git tag -a {release_tag} -m 'Cofrinho {version}'", file=sys.stderr)
        print(f"Then: git push origin refs/tags/{release_tag}:refs/tags/{release_tag}", file=sys.stderr)
        raise


def release_patch(workspace: _ReleaseWorkspace) -> str:
    """Commit and publish the next patch. Example: release_patch(_ReleaseWorkspace(Path.cwd()))."""
    _require_clean_master(workspace)
    update = _plan_patch(workspace)
    _check_remote_release(workspace, f"v{update.version}")
    _commit_patch(workspace, update)
    _publish_patch(workspace, update.version)
    return update.version


def main() -> int:
    """Run the release command. Example: python3 scripts/bump.py."""
    argparse.ArgumentParser(description="Bump the patch version and Android code, commit, push master, and publish a version tag.").parse_args()
    try:
        version = release_patch(_ReleaseWorkspace(Path(__file__).resolve().parents[1]))
    except subprocess.CalledProcessError as error:
        print(f"Release stopped: {(error.stderr or str(error)).strip()}", file=sys.stderr)
        return 1
    except (ValueError, OSError) as error:
        print(f"Release stopped: {error}", file=sys.stderr)
        return 1
    print(f"Published v{version}; GitHub Actions will build the Android and CLI release.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
