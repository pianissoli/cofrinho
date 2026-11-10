"""Installer checks. Run: python3 -m unittest discover -s scripts -p 'test_*.py'."""

from __future__ import annotations

import hashlib
from pathlib import Path
import os
import stat
import tarfile
import tempfile
import unittest

from scripts.install_cli import InstallOptions, InstallerError, _install_lock, install


class ReleaseFixture:
    """Build a tiny self-contained release archive for installer tests."""

    def __init__(self, root: Path, version: str = "v0.2.0", failing: bool = False) -> None:
        self.root = root
        self.version = version
        self.archive = root / f"cofrinho-{version}-linux-x86_64.tar.gz"
        self._create(failing)

    def _create(self, failing: bool) -> None:
        source = self.root / f"source-{self.version}"
        (source / "bin").mkdir(parents=True)
        (source / "lib").mkdir()
        (source / "jre" / "bin").mkdir(parents=True)
        (source / "jre" / "legal").mkdir(parents=True)
        launcher = source / "bin" / "cofrinho"
        outcome = "exit 7" if failing else "printf 'cofrinho help\\n'\nexit 0"
        launcher.write_text(f"#!/bin/sh\n# Cofrinho APP_HOME\nCLASSPATH=$APP_HOME/lib/cofrinho.jar\n{outcome}\n", encoding="utf-8")
        launcher.chmod(0o755)
        java = source / "jre" / "bin" / "java"
        java.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
        java.chmod(0o755)
        (source / "lib" / "cofrinho.jar").write_bytes(self.version.encode())
        (source / "lib" / "old.jar").write_bytes(b"old dependency")
        (source / "THIRD_PARTY_NOTICES.txt").write_text("notice\n", encoding="utf-8")
        (source / "jre" / "legal" / "LICENSE.txt").write_text("license\n", encoding="utf-8")
        (source / "jre" / "legal" / "LICENSE.link").symlink_to("LICENSE.txt")
        with tarfile.open(self.archive, "w:gz", dereference=False) as handle:
            handle.add(source, arcname="cofrinho-linux-x86_64")
        digest = hashlib.sha256(self.archive.read_bytes()).hexdigest()
        self.archive.with_name(f"{self.archive.name}.sha256").write_text(f"{digest}  {self.archive.name}\n", encoding="utf-8")


class InstallerTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="cofrinho-installer-test-")
        self.root = Path(self.temporary.name)
        self.home = self.root / "home"
        self.home.mkdir()
        self.install_dir = self.home / "opt" / "cofrinho"
        self.bin_dir = self.home / "bin"

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def options(self, archive: Path, **overrides: object) -> InstallOptions:
        values: dict[str, object] = {
            "archive": archive,
            "checksum": None,
            "install_dir": self.install_dir,
            "bin_dir": self.bin_dir,
            "retired": (),
            "dry_run": False,
        }
        values.update(overrides)
        return InstallOptions(**values)  # type: ignore[arg-type]

    def test_first_install_and_financial_files_are_untouched(self) -> None:
        fixture = ReleaseFixture(self.root)
        ledger = self.home / "share" / "cofrinho" / "ledger.sqlite"
        ledger.parent.mkdir(parents=True)
        ledger.write_bytes(b"incompatible schema bytes")
        ledger.chmod(0o640)
        backup = ledger.parent / "backups" / "ledger.sqlite.bak"
        backup.parent.mkdir()
        backup.write_bytes(b"backup")
        install(self.options(fixture.archive))
        self.assertEqual(ledger.read_bytes(), b"incompatible schema bytes")
        self.assertEqual(stat.S_IMODE(ledger.stat().st_mode), 0o640)
        self.assertEqual(backup.read_bytes(), b"backup")
        self.assertEqual((self.bin_dir / "cofrinho").resolve(), self.install_dir / "bin" / "cofrinho")
        self.assertTrue((self.install_dir / "jre" / "legal" / "LICENSE.link").is_symlink())

    def test_upgrade_replaces_stale_program_files(self) -> None:
        first = ReleaseFixture(self.root, "v0.2.0")
        second = ReleaseFixture(self.root, "v0.2.1")
        install(self.options(first.archive))
        (self.install_dir / "lib" / "manual-stale.jar").write_bytes(b"stale")
        install(self.options(second.archive))
        self.assertFalse((self.install_dir / "lib" / "manual-stale.jar").exists())
        self.assertEqual((self.install_dir / "lib" / "cofrinho.jar").read_bytes(), b"v0.2.1")

    def test_explicit_retirement_removes_old_version_directory(self) -> None:
        fixture = ReleaseFixture(self.root)
        old = self.home / "opt" / "cofrinho-v0.1.2"
        install(self.options(fixture.archive, install_dir=old, bin_dir=self.bin_dir))
        new = self.home / "opt" / "cofrinho"
        install(self.options(fixture.archive, install_dir=new, bin_dir=self.bin_dir, retired=(old,)))
        self.assertTrue(new.is_dir())
        self.assertFalse(old.exists())

    def test_reinstall_is_idempotent(self) -> None:
        fixture = ReleaseFixture(self.root)
        install(self.options(fixture.archive))
        install(self.options(fixture.archive))
        self.assertTrue(self.install_dir.is_dir())
        self.assertEqual(len(list(self.install_dir.parent.glob(".cofrinho.rollback-*"))), 0)

    def test_bad_checksum_preserves_existing_install(self) -> None:
        good = ReleaseFixture(self.root, "v0.2.0")
        bad = ReleaseFixture(self.root, "v0.2.1")
        install(self.options(good.archive))
        bad.archive.with_name(f"{bad.archive.name}.sha256").write_text("0" * 64 + "  wrong.tar.gz\n", encoding="utf-8")
        with self.assertRaises(InstallerError):
            install(self.options(bad.archive))
        self.assertEqual((self.install_dir / "lib" / "cofrinho.jar").read_bytes(), b"v0.2.0")

    def test_unexpected_database_in_install_is_refused(self) -> None:
        fixture = ReleaseFixture(self.root)
        self.install_dir.mkdir(parents=True)
        (self.install_dir / "ledger.sqlite").write_bytes(b"do not delete")
        with self.assertRaises(InstallerError):
            install(self.options(fixture.archive))
        self.assertEqual((self.install_dir / "ledger.sqlite").read_bytes(), b"do not delete")

    def test_dry_run_does_not_create_install_or_command(self) -> None:
        fixture = ReleaseFixture(self.root)
        install(self.options(fixture.archive, dry_run=True))
        self.assertFalse(self.install_dir.exists())
        self.assertFalse(self.install_dir.parent.exists())
        self.assertFalse((self.bin_dir / "cofrinho").exists())

    def test_custom_paths_with_spaces_work(self) -> None:
        fixture = ReleaseFixture(self.root)
        install_dir = self.home / "program files" / "cofrinho"
        bin_dir = self.home / "command links"
        install(self.options(fixture.archive, install_dir=install_dir, bin_dir=bin_dir))
        self.assertEqual((bin_dir / "cofrinho").resolve(), install_dir / "bin" / "cofrinho")

    def test_missing_runtime_is_rejected_before_replacement(self) -> None:
        good = ReleaseFixture(self.root, "v0.2.0")
        bad = ReleaseFixture(self.root, "v0.2.1")
        install(self.options(good.archive))
        runtime = self.root / "source-v0.2.1" / "jre" / "bin" / "java"
        runtime.unlink()
        with tarfile.open(bad.archive, "w:gz", dereference=False) as handle:
            handle.add(self.root / "source-v0.2.1", arcname="cofrinho-linux-x86_64")
        digest = hashlib.sha256(bad.archive.read_bytes()).hexdigest()
        bad.archive.with_name(f"{bad.archive.name}.sha256").write_text(f"{digest}  {bad.archive.name}\n", encoding="utf-8")
        with self.assertRaises(InstallerError):
            install(self.options(bad.archive))
        self.assertEqual((self.install_dir / "lib" / "cofrinho.jar").read_bytes(), b"v0.2.0")

    def test_unexplained_rollback_artifact_stops_install(self) -> None:
        fixture = ReleaseFixture(self.root)
        artifact = self.install_dir.parent / ".cofrinho.rollback-leftover"
        artifact.mkdir(parents=True)
        with self.assertRaises(InstallerError):
            install(self.options(fixture.archive))
        self.assertTrue(artifact.is_dir())

    def test_install_lock_rejects_second_holder(self) -> None:
        fixture = ReleaseFixture(self.root)
        self.install_dir.parent.mkdir(parents=True)
        with _install_lock(self.install_dir):
            with self.assertRaises(InstallerError):
                install(self.options(fixture.archive))

    def test_failed_new_smoke_restores_old_install(self) -> None:
        good = ReleaseFixture(self.root, "v0.2.0")
        bad = ReleaseFixture(self.root, "v0.2.1", failing=True)
        install(self.options(good.archive))
        with self.assertRaises(InstallerError):
            install(self.options(bad.archive))
        self.assertEqual((self.install_dir / "lib" / "cofrinho.jar").read_bytes(), b"v0.2.0")
        self.assertEqual((self.bin_dir / "cofrinho").resolve(), self.install_dir / "bin" / "cofrinho")

    def test_outward_archive_link_is_rejected(self) -> None:
        fixture = ReleaseFixture(self.root)
        source = self.root / "source-v0.2.0" / "jre" / "legal" / "LICENSE.link"
        source.unlink()
        source.symlink_to("../../../../outside")
        with tarfile.open(fixture.archive, "w:gz", dereference=False) as handle:
            handle.add(self.root / "source-v0.2.0", arcname="cofrinho-linux-x86_64")
        digest = hashlib.sha256(fixture.archive.read_bytes()).hexdigest()
        fixture.archive.with_name(f"{fixture.archive.name}.sha256").write_text(f"{digest}  {fixture.archive.name}\n", encoding="utf-8")
        with self.assertRaises(InstallerError):
            install(self.options(fixture.archive))

    def test_regular_command_file_is_not_overwritten(self) -> None:
        fixture = ReleaseFixture(self.root)
        self.bin_dir.mkdir()
        command = self.bin_dir / "cofrinho"
        command.write_text("user command\n", encoding="utf-8")
        with self.assertRaises(InstallerError):
            install(self.options(fixture.archive))
        self.assertEqual(command.read_text(encoding="utf-8"), "user command\n")


if __name__ == "__main__":
    unittest.main()
