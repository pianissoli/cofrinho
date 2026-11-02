"""Release-script checks. Run: python3 -m unittest discover -s scripts -p 'test_*.py'."""

from contextlib import redirect_stderr
from dataclasses import dataclass, field
from io import StringIO
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from scripts.bump import _ReleaseWorkspace, release_patch


_ROOT_CONTENTS: str = '# Keep project provenance.\nversion = "0.1.0"\n'
_ANDROID_CONTENTS: str = 'android {\n    versionCode = 1\n    versionName = "0.1.0"\n}\n'


@dataclass(frozen=True)
class FakeReleaseWorkspace(_ReleaseWorkspace):
    documents: dict[str, str] = field(default_factory=dict)
    commands: list[tuple[str, ...]] = field(default_factory=list)
    responses: dict[tuple[str, ...], str] = field(default_factory=dict)
    failure: tuple[str, ...] | None = None

    def read(self, relative_path: str) -> str:
        return self.documents[relative_path]

    def write(self, relative_path: str, contents: str) -> None:
        self.documents[relative_path] = contents

    def git(self, *arguments: str) -> str:
        self.commands.append(arguments)
        if arguments == self.failure:
            raise subprocess.CalledProcessError(1, ["git", *arguments], stderr="fake Git failure")
        return self.responses.get(arguments, "")


def release_fixture() -> FakeReleaseWorkspace:
    documents = {"build.gradle.kts": _ROOT_CONTENTS, "apps/android/build.gradle.kts": _ANDROID_CONTENTS}
    return FakeReleaseWorkspace(
        Path("/fake/cofrinho"), documents=documents,
        responses={
            ("branch", "--show-current"): "master",
            ("rev-list", "--left-right", "--count", "origin/master...HEAD"): "0\t0",
        },
    )


def local_release_fixture(directory: Path) -> _ReleaseWorkspace:
    checkout = directory / "checkout"
    checkout.mkdir()
    workspace = _ReleaseWorkspace(checkout)
    workspace.git("init", "--bare", "--initial-branch=master", str(directory / "origin.git"))
    workspace.git("init", "--initial-branch=master")
    workspace.git("config", "user.name", "Release Test")
    workspace.git("config", "user.email", "release-test@example.invalid")
    workspace.git("config", "commit.gpgSign", "false")
    workspace.git("config", "tag.gpgSign", "false")
    workspace.git("config", "core.hooksPath", "/dev/null")
    workspace.git("remote", "add", "origin", str(directory / "origin.git"))
    (checkout / "apps/android").mkdir(parents=True)
    workspace.write("build.gradle.kts", _ROOT_CONTENTS)
    workspace.write("apps/android/build.gradle.kts", _ANDROID_CONTENTS)
    workspace.git("add", "--all")
    workspace.git("commit", "-m", "test: initialize version sources")
    workspace.git("push", "origin", "HEAD:refs/heads/master")
    return workspace


class PatchReleaseTests(unittest.TestCase):
    def test_publishes_a_real_commit_and_annotated_tag_to_local_remote(self) -> None:
        with tempfile.TemporaryDirectory(prefix="cofrinho-bump-test-") as directory:
            workspace = local_release_fixture(Path(directory))
            self.assertEqual(release_patch(workspace), "0.1.1")
            commit = workspace.git("rev-parse", "HEAD")
            remote_commit = workspace.git("ls-remote", "--heads", "origin", "refs/heads/master").split()[0]
            self.assertEqual(commit, remote_commit)
            self.assertEqual(workspace.git("cat-file", "-t", "v0.1.1"), "tag")
            self.assertEqual(workspace.git("rev-parse", "v0.1.1^{}"), commit)
            self.assertIn("refs/tags/v0.1.1", workspace.git("ls-remote", "--tags", "origin"))
            self.assertEqual(workspace.git("status", "--porcelain"), "")

    def test_updates_versions_and_publishes_only_the_new_tag(self) -> None:
        workspace = release_fixture()
        self.assertEqual(release_patch(workspace), "0.1.1")
        self.assertEqual(workspace.documents["build.gradle.kts"], _ROOT_CONTENTS.replace("0.1.0", "0.1.1"))
        self.assertEqual(workspace.documents["apps/android/build.gradle.kts"], _ANDROID_CONTENTS.replace("0.1.0", "0.1.1").replace("versionCode = 1", "versionCode = 2"))
        self.assertEqual(workspace.commands[-3:], [
            ("push", "origin", "HEAD:refs/heads/master"),
            ("tag", "-a", "v0.1.1", "-m", "Cofrinho 0.1.1"),
            ("push", "origin", "refs/tags/v0.1.1:refs/tags/v0.1.1"),
        ])
        self.assertIn(("commit", "-m", "chore: bump version to 0.1.1"), workspace.commands)

    def test_only_patch_and_android_code_increase(self) -> None:
        workspace = release_fixture()
        workspace.documents["build.gradle.kts"] = 'version = "12.34.99"\n'
        workspace.documents["apps/android/build.gradle.kts"] = 'versionName = "12.34.99"\nversionCode = 42\n'
        self.assertEqual(release_patch(workspace), "12.34.100")
        self.assertIn('versionCode = 43', workspace.documents["apps/android/build.gradle.kts"])

    def test_rejects_dirty_working_tree_before_writing(self) -> None:
        workspace = release_fixture()
        workspace.responses[("status", "--porcelain")] = " M README.md"
        with self.assertRaisesRegex(ValueError, "clean checkout"):
            release_patch(workspace)
        self.assertEqual(workspace.documents["build.gradle.kts"], _ROOT_CONTENTS)
        self.assertEqual(len(workspace.commands), 1)

    def test_rejects_another_branch(self) -> None:
        workspace = release_fixture()
        workspace.responses[("branch", "--show-current")] = "feature"
        with self.assertRaisesRegex(ValueError, "expected 'master'"):
            release_patch(workspace)
        self.assertEqual(workspace.documents["build.gradle.kts"], _ROOT_CONTENTS)

    def test_rejects_mismatched_versions_before_contacting_remote(self) -> None:
        workspace = release_fixture()
        workspace.documents["build.gradle.kts"] = 'version = "0.2.0"\n'
        with self.assertRaisesRegex(ValueError, "identical project and Android versions"):
            release_patch(workspace)
        self.assertFalse(any(command[0] == "fetch" for command in workspace.commands))

    def test_rejects_missing_duplicate_and_noncanonical_assignments(self) -> None:
        for contents in ('', 'version = "0.1.0"\nversion = "0.1.0"\n', 'version = "00.1.0"\n'):
            with self.subTest(contents=contents):
                workspace = release_fixture()
                workspace.documents["build.gradle.kts"] = contents
                with self.assertRaisesRegex(ValueError, "expected exactly one"):
                    release_patch(workspace)
                self.assertEqual(workspace.documents["apps/android/build.gradle.kts"], _ANDROID_CONTENTS)

    def test_rejects_nonpositive_or_exhausted_android_code(self) -> None:
        for version_code in (0, 2_147_483_647):
            with self.subTest(version_code=version_code):
                workspace = release_fixture()
                workspace.documents["apps/android/build.gradle.kts"] = _ANDROID_CONTENTS.replace("versionCode = 1", f"versionCode = {version_code}")
                with self.assertRaisesRegex(ValueError, "cannot advance"):
                    release_patch(workspace)
                self.assertEqual(workspace.documents["build.gradle.kts"], _ROOT_CONTENTS)

    def test_rejects_existing_release_tag(self) -> None:
        workspace = release_fixture()
        workspace.responses[("tag", "--list", "v0.1.1")] = "v0.1.1"
        with self.assertRaisesRegex(ValueError, "already exists"):
            release_patch(workspace)
        self.assertEqual(workspace.documents["build.gradle.kts"], _ROOT_CONTENTS)

    def test_rejects_remote_history_missing_locally(self) -> None:
        workspace = release_fixture()
        workspace.responses[("rev-list", "--left-right", "--count", "origin/master...HEAD")] = "1\t0"
        with self.assertRaisesRegex(ValueError, "remote history"):
            release_patch(workspace)
        self.assertEqual(workspace.documents["build.gradle.kts"], _ROOT_CONTENTS)

    def test_unavailable_remote_preserves_versions(self) -> None:
        fixture = release_fixture()
        workspace = FakeReleaseWorkspace(fixture.root, fixture.documents, responses=fixture.responses, failure=("fetch", "origin", "master", "--tags"))
        with self.assertRaises(subprocess.CalledProcessError):
            release_patch(workspace)
        self.assertEqual(workspace.documents["build.gradle.kts"], _ROOT_CONTENTS)
        self.assertEqual(workspace.documents["apps/android/build.gradle.kts"], _ANDROID_CONTENTS)

    def test_failed_master_push_preserves_commit_and_does_not_tag(self) -> None:
        fixture = release_fixture()
        workspace = FakeReleaseWorkspace(fixture.root, fixture.documents, responses=fixture.responses, failure=("push", "origin", "HEAD:refs/heads/master"))
        diagnostics = StringIO()
        with redirect_stderr(diagnostics), self.assertRaises(subprocess.CalledProcessError):
            release_patch(workspace)
        self.assertIn(("commit", "-m", "chore: bump version to 0.1.1"), workspace.commands)
        self.assertNotIn(("tag", "-a", "v0.1.1", "-m", "Cofrinho 0.1.1"), workspace.commands)
        self.assertIn("Do not run another bump", diagnostics.getvalue())

    def test_failed_tag_push_preserves_annotated_tag_and_prints_recovery(self) -> None:
        fixture = release_fixture()
        workspace = FakeReleaseWorkspace(fixture.root, fixture.documents, responses=fixture.responses, failure=("push", "origin", "refs/tags/v0.1.1:refs/tags/v0.1.1"))
        diagnostics = StringIO()
        with redirect_stderr(diagnostics), self.assertRaises(subprocess.CalledProcessError):
            release_patch(workspace)
        self.assertIn(("tag", "-a", "v0.1.1", "-m", "Cofrinho 0.1.1"), workspace.commands)
        self.assertIn("git push origin refs/tags/v0.1.1:refs/tags/v0.1.1", diagnostics.getvalue())

    def test_failed_commit_does_not_push_or_tag(self) -> None:
        fixture = release_fixture()
        workspace = FakeReleaseWorkspace(fixture.root, fixture.documents, responses=fixture.responses, failure=("commit", "-m", "chore: bump version to 0.1.1"))
        with self.assertRaises(subprocess.CalledProcessError):
            release_patch(workspace)
        self.assertFalse(any(command[0] in ("push", "tag") and command[:2] != ("tag", "--list") for command in workspace.commands))

    def test_command_help_does_not_start_a_release(self) -> None:
        script = Path(__file__).with_name("bump.py")
        completed = subprocess.run([sys.executable, str(script), "--help"], check=True, capture_output=True, text=True)
        self.assertIn("Bump the patch version", completed.stdout)


if __name__ == "__main__":
    unittest.main()
