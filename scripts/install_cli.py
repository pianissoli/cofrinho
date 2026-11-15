#!/usr/bin/env python3
"""Install a downloaded Cofrinho Linux x86_64 release without touching ledgers."""

from __future__ import annotations

import argparse
import contextlib
import dataclasses
import fcntl
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import platform
import posixpath
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
from typing import Iterator, NoReturn, Sequence


PACKAGE_ROOT = "cofrinho-linux-x86_64"
MAX_MEMBERS = 100_000
MAX_ARCHIVE_BYTES = 2 * 1024 * 1024 * 1024
MAX_SMOKE_SECONDS = 20
ARCHIVE_PATTERN = re.compile(r"^cofrinho-(v[0-9]+\.[0-9]+\.[0-9]+)-linux-x86_64\.tar\.gz$")
CHECKSUM_PATTERN = re.compile(r"^([0-9a-fA-F]{64})\s+\*?(.+)$")


class InstallerError(RuntimeError):
    """A user-actionable installer failure."""


class CleanupError(InstallerError):
    """Activation succeeded but an old program tree remains."""


@dataclasses.dataclass(frozen=True)
class InstallOptions:
    archive: Path
    checksum: Path | None
    install_dir: Path
    bin_dir: Path
    retired: tuple[Path, ...]
    dry_run: bool


@dataclasses.dataclass(frozen=True)
class ArchiveMember:
    info: tarfile.TarInfo
    relative: PurePosixPath


@dataclasses.dataclass(frozen=True)
class InstallPlan:
    options: InstallOptions
    archive_digest: str
    version: str
    members: tuple[ArchiveMember, ...]
    previous_link: str | None


def _resolved(path: Path) -> Path:
    return path.expanduser().resolve(strict=False)


def _inside(path: Path, parent: Path) -> bool:
    try:
        path.relative_to(parent)
    except ValueError:
        return False
    return True


def _error(value: object, expected: str) -> NoReturn:
    raise InstallerError(f"Invalid {value!r}; expected {expected}")


def _member_relative(name: str) -> PurePosixPath:
    if "\\" in name or name.startswith("/"):
        _error(name, f"a relative archive member below {PACKAGE_ROOT}/")
    parts = PurePosixPath(name).parts
    if not parts or parts[0] != PACKAGE_ROOT or ".." in parts:
        _error(name, f"a path below {PACKAGE_ROOT}/ without traversal")
    relative = PurePosixPath(*parts[1:])
    return PurePosixPath() if str(relative) in {"", "."} else relative


def _validate_link_target(member: ArchiveMember) -> None:
    if not member.info.issym():
        return
    target = member.info.linkname
    if target.startswith("/") or "\\" in target:
        _error(target, "a relative link target inside the release")
    combined = posixpath.normpath(posixpath.join(str(member.relative.parent), target))
    if combined == ".." or combined.startswith("../"):
        _error(target, "a link target that remains inside the release")


def _validate_member_set(members: Sequence[ArchiveMember]) -> None:
    names: set[PurePosixPath] = set()
    total = 0
    for member in members:
        if member.relative in names:
            _error(member.info.name, "an archive without duplicate members")
        names.add(member.relative)
        if member.info.isreg():
            total += member.info.size
        if member.info.isdev() or member.info.isfifo() or member.info.islnk():
            _error(member.info.name, "a regular file, directory, or safe relative symlink")
        _validate_link_target(member)
    if total > MAX_ARCHIVE_BYTES:
        _error(total, f"an extracted archive no larger than {MAX_ARCHIVE_BYTES} bytes")
    root = PurePosixPath()
    if root not in names:
        _error(PACKAGE_ROOT, "an archive root directory entry")
    if not next(member for member in members if member.relative == root).info.isdir():
        _error(PACKAGE_ROOT, "a directory archive root")
    kinds = {member.relative: member.info.isdir() for member in members}
    for name in names:
        parent = name.parent
        while parent != name and parent != PurePosixPath():
            if parent in kinds and not kinds[parent]:
                _error(str(name), "members whose parents are directories")
            name = parent


def _required_member(members: Sequence[ArchiveMember], path: str) -> ArchiveMember:
    for member in members:
        if str(member.relative) == path:
            return member
    _error(path, "a required release member")


def _validate_package_shape(members: Sequence[ArchiveMember]) -> None:
    required = ("bin/cofrinho", "jre/bin/java", "THIRD_PARTY_NOTICES.txt")
    for path in required:
        member = _required_member(members, path)
        if not member.info.isreg():
            _error(path, "a regular file")
    for executable in ("bin/cofrinho", "jre/bin/java"):
        member = _required_member(members, executable)
        if not member.info.mode & 0o111:
            _error(executable, "an executable release member")
    if not any(member.relative.parts[:1] == ("lib",) and member.info.name.endswith(".jar") for member in members):
        _error("lib", "at least one application/dependency JAR")


def _validate_archive(archive: Path) -> tuple[ArchiveMember, ...]:
    try:
        handle = tarfile.open(archive, mode="r:gz")
    except (tarfile.TarError, OSError) as error:
        raise InstallerError(f"Cannot read archive {archive!r}; expected a valid .tar.gz: {error}") from error
    with handle:
        members = handle.getmembers()
    if len(members) > MAX_MEMBERS:
        _error(len(members), f"at most {MAX_MEMBERS} archive members")
    wrapped = tuple(ArchiveMember(member, _member_relative(member.name)) for member in members)
    _validate_member_set(wrapped)
    _validate_package_shape(wrapped)
    return wrapped


def _ensure_parent_without_symlink(path: Path, root: Path) -> None:
    parent = path.parent
    while parent != root:
        if parent.exists() and parent.is_symlink():
            _error(parent, "a non-symlink extraction parent")
        parent = parent.parent


def _extract_archive(archive: Path, members: Sequence[ArchiveMember], destination: Path) -> None:
    with tarfile.open(archive, mode="r:gz") as handle:
        for member in members:
            if not member.relative.parts:
                continue
            output = destination.joinpath(*member.relative.parts)
            _ensure_parent_without_symlink(output, destination)
            if member.info.isdir():
                output.mkdir(mode=member.info.mode & 0o777, exist_ok=True)
            elif member.info.issym():
                output.symlink_to(member.info.linkname)
            elif member.info.isreg():
                extracted = handle.extractfile(member.info)
                if extracted is None:
                    _error(member.info.name, "a readable regular archive member")
                with extracted, output.open("xb") as target:
                    shutil.copyfileobj(extracted, target)
                output.chmod(member.info.mode & 0o777)
    _validate_distribution(destination)


def _safe_link_target(path: Path, root: Path) -> Path:
    if not path.is_symlink():
        return path
    target = os.readlink(path)
    if os.path.isabs(target):
        _error(path, "a relative internal symlink")
    resolved = (path.parent / target).resolve(strict=False)
    if not _inside(resolved, root):
        _error(path, "a symlink target inside its distribution")
    return resolved


def _walk_distribution(root: Path) -> Iterator[Path]:
    for directory, directories, files in os.walk(root, followlinks=False):
        current = Path(directory)
        for name in directories + files:
            yield current / name


def _reject_financial_entries(entries: Iterator[Path]) -> None:
    suffixes = (".sqlite", ".sqlite3", ".db", "-wal", "-shm", "-journal", ".cofrinho")
    names = {"backups", "exports", "transfer-state"}
    for entry in entries:
        lowered = entry.name.lower()
        if lowered in names or lowered.endswith(suffixes):
            _error(entry, "a program-only distribution without financial files")


def _validate_program_directories(root: Path) -> None:
    bin_names = {entry.name for entry in (root / "bin").iterdir()}
    if not bin_names.issubset({"cofrinho", "cofrinho.bat"}):
        _error(sorted(bin_names - {"cofrinho", "cofrinho.bat"}), "only the Cofrinho launcher in bin/")
    library = root / "lib"
    if not library.is_dir() or any(not entry.is_file() or entry.is_symlink() or entry.suffix != ".jar" for entry in library.iterdir()):
        _error(library, "only regular application/dependency JARs in lib/")


def _launcher_is_cofrinho(path: Path) -> bool:
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return False
    return "APP_HOME" in text and "cofrinho" in text.lower()


def _application_jar(launcher: Path, library: Path) -> Path:
    text = launcher.read_text(encoding="utf-8", errors="replace")
    names = re.findall(r"\$APP_HOME/lib/([^:$\s]+\.jar)", text)
    candidate = library / (names[0] if names else "cofrinho.jar")
    if not candidate.is_file() or candidate.is_symlink():
        _error(candidate, "the application JAR referenced by the Cofrinho launcher")
    return candidate


def _validate_distribution(root: Path) -> None:
    if not root.is_dir() or root.is_symlink():
        _error(root, "a real Cofrinho distribution directory")
    allowed = {"bin", "lib", "jre", "THIRD_PARTY_NOTICES.txt", ".cofrinho-install.json"}
    top = {entry.name for entry in root.iterdir()}
    if not top.issubset(allowed):
        _error(sorted(top - allowed), "only Cofrinho release files, with no user data")
    marker = root / ".cofrinho-install.json"
    if marker.exists():
        try:
            identity = json.loads(marker.read_text(encoding="utf-8"))
        except (OSError, ValueError) as error:
            raise InstallerError(f"Cannot read installer identity {marker!r}; expected Cofrinho metadata: {error}") from error
        if not isinstance(identity, dict) or identity.get("installer") != "cofrinho":
            _error(marker, "Cofrinho installer identity metadata")
    entries = tuple(_walk_distribution(root))
    for entry in entries:
        if entry.is_symlink():
            _safe_link_target(entry, root)
    _reject_financial_entries(iter(entries))
    launcher = root / "bin" / "cofrinho"
    java = root / "jre" / "bin" / "java"
    notices = root / "THIRD_PARTY_NOTICES.txt"
    for path in (launcher, java, notices):
        if not path.is_file() or path.is_symlink():
            _error(path, "a regular Cofrinho release file")
    if not os.access(launcher, os.X_OK) or not os.access(java, os.X_OK):
        _error(launcher, "an executable Cofrinho launcher and bundled Java runtime")
    if not _launcher_is_cofrinho(launcher):
        _error(launcher, "the generated Cofrinho launcher, not an unrelated executable")
    _validate_program_directories(root)
    _application_jar(launcher, root / "lib")


def _require_linux_x86_64() -> None:
    if hasattr(os, "geteuid") and os.geteuid() == 0:
        _error(os.geteuid(), "a non-root user installation")
    if not sys.platform.startswith("linux"):
        _error(sys.platform, "a Linux host")
    if platform.machine().lower() not in {"x86_64", "amd64"}:
        _error(platform.machine(), "an x86_64 host")


def _protected_paths(home: Path) -> tuple[Path, ...]:
    return (home, home / ".local", home / ".local" / "share", home / ".local" / "share" / "cofrinho")


def _validate_destination(path: Path, home: Path, label: str) -> Path:
    resolved = _resolved(path)
    financial_root = home / ".local" / "share" / "cofrinho"
    if resolved == Path("/") or resolved in _protected_paths(home) or _inside(resolved, financial_root):
        _error(path, f"a narrow writable {label} path, not a protected directory")
    if len(resolved.parts) < 3:
        _error(path, f"a per-user {label} path, not a broad filesystem directory")
    if path.exists() and path.is_symlink():
        _error(path, f"a real {label} directory, not a symlink")
    return resolved


def _validate_parent_access(path: Path) -> None:
    parent = path.parent
    while not parent.exists() and parent != parent.parent:
        parent = parent.parent
    if not os.access(parent, os.W_OK | os.X_OK):
        _error(parent, "a writable parent directory for installation")


def _validate_paths(options: InstallOptions) -> InstallOptions:
    home = _resolved(Path.home())
    archive = _resolved(options.archive)
    install = _validate_destination(options.install_dir, home, "distribution")
    bindir = _validate_destination(options.bin_dir, home, "command")
    retired = tuple(_validate_destination(path, home, "retirement") for path in options.retired)
    for destination in (install, bindir, *retired):
        _validate_parent_access(destination)
    if not archive.is_file():
        _error(archive, "an existing .tar.gz release archive")
    all_paths = (archive, install, bindir, *retired)
    for index, first in enumerate(all_paths):
        for second in all_paths[index + 1 :]:
            if _inside(first, second) or _inside(second, first):
                _error(f"{first} and {second}", "non-overlapping paths")
    return dataclasses.replace(options, archive=archive, install_dir=install, bin_dir=bindir, retired=retired)


def _read_checksum(path: Path, archive: Path) -> str:
    if not path.is_file():
        _error(path, "an existing SHA-256 checksum file")
    matches: list[str] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        match = CHECKSUM_PATTERN.fullmatch(line.strip())
        if match and Path(match.group(2)).name == archive.name:
            matches.append(match.group(1).lower())
    if len(matches) != 1:
        _error(path, "exactly one SHA-256 line naming the release archive")
    return matches[0]


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _verify_checksum(archive: Path, checksum: Path) -> str:
    expected = _read_checksum(checksum, archive)
    actual = _sha256(archive)
    if actual != expected:
        raise InstallerError(f"Checksum {actual!r} for {archive} differs from expected {expected!r}")
    return actual


def _validate_existing(path: Path) -> None:
    if path.exists() or path.is_symlink():
        _validate_distribution(path)


def _command_target(path: Path) -> str | None:
    if not path.exists() and not path.is_symlink():
        return None
    if not path.is_symlink():
        _error(path, "a symlink to a Cofrinho launcher, not a regular file or directory")
    target = path.resolve(strict=False)
    if not target.is_file():
        _error(path, "a symlink whose target exists")
    _validate_distribution(target.parent.parent)
    return os.readlink(path)


def _validate_version(archive: Path) -> str:
    match = ARCHIVE_PATTERN.fullmatch(archive.name)
    if not match:
        _error(archive.name, "a release named cofrinho-vMAJOR.MINOR.PATCH-linux-x86_64.tar.gz")
    return match.group(1)


def _make_plan(options: InstallOptions) -> InstallPlan:
    _require_linux_x86_64()
    normalized = _validate_paths(options)
    checksum = _resolved(normalized.checksum or Path(f"{normalized.archive}.sha256"))
    digest = _verify_checksum(normalized.archive, checksum)
    members = _validate_archive(normalized.archive)
    _validate_existing(normalized.install_dir)
    for path in normalized.retired:
        _validate_existing(path)
    previous = _command_target(normalized.bin_dir / "cofrinho")
    return InstallPlan(normalized, digest, _validate_version(normalized.archive), members, previous)


def _state_path(install: Path) -> Path:
    return install.parent / f".{install.name}.install-state.json"


def _write_state(path: Path, state: dict[str, object]) -> None:
    temporary = path.with_name(f".{path.name}.tmp-{os.getpid()}")
    temporary.write_text(json.dumps(state, sort_keys=True), encoding="utf-8")
    temporary.chmod(0o600)
    os.replace(temporary, path)


def _load_state(path: Path) -> dict[str, object] | None:
    if not path.exists():
        return None
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise InstallerError(f"Cannot recover installer state {path!r}; expected valid installer metadata: {error}") from error
    if not isinstance(value, dict) or not isinstance(value.get("phase"), str):
        _error(path, "installer metadata with a phase")
    return value


def _remove_state(path: Path) -> None:
    path.unlink(missing_ok=True)


def _restore_link(path: Path, target: str | None) -> None:
    path.unlink(missing_ok=True)
    if target is not None:
        path.parent.mkdir(mode=0o755, parents=True, exist_ok=True)
        path.symlink_to(target)


def _remove_tree(path: Path) -> None:
    if path.exists() or path.is_symlink():
        path.unlink() if path.is_symlink() else shutil.rmtree(path)


def _recover_state(path: Path) -> None:
    state = _load_state(path)
    if state is None:
        return
    phase = str(state["phase"])
    if phase == "cleanup-pending":
        _finish_cleanup(path, state)
        return
    if phase == "staging":
        _remove_tree(Path(str(state["stage"])))
        _remove_state(path)
        return
    if phase not in {"old-renamed", "new-activated", "link-switched"}:
        _error(path, "known installer recovery metadata")
    _restore_interrupted(path, state)


def _check_unexplained_artifacts(install: Path) -> None:
    patterns = (f".{install.name}.rollback-*", f".{install.name}.staging-*")
    artifacts = [candidate for pattern in patterns for candidate in install.parent.glob(pattern)]
    if artifacts:
        raise InstallerError(f"Unexplained installer artifacts {artifacts!r}; inspect or recover them before retrying")


def _restore_interrupted(path: Path, state: dict[str, object]) -> None:
    install = Path(str(state["install"]))
    stage = Path(str(state["stage"]))
    rollback = Path(str(state["rollback"]))
    link = Path(str(state["link"]))
    if not bool(state.get("had_old")):
        if state.get("phase") in {"new-activated", "link-switched"} and install.exists():
            _validate_distribution(install)
            if state.get("phase") == "new-activated":
                _replace_link(link, install / "bin" / "cofrinho")
        _remove_tree(stage)
        _remove_state(path)
        return
    if rollback.exists() and install.exists():
        _validate_distribution(install)
        _remove_tree(install)
    if rollback.exists():
        _validate_distribution(rollback)
        os.replace(rollback, install)
    else:
        _error(rollback, "the rollback distribution recorded for interrupted recovery")
    _restore_link(link, state.get("previous_link") if isinstance(state.get("previous_link"), str) else None)
    _remove_tree(stage)
    _remove_state(path)


def _finish_cleanup(path: Path, state: dict[str, object]) -> None:
    failures: list[Path] = []
    for value in state.get("cleanup", []):
        leftover = Path(str(value))
        try:
            _remove_tree(leftover)
        except OSError:
            failures.append(leftover)
    if failures:
        raise CleanupError(f"Cleanup still pending for validated old program paths: {failures}")
    _remove_state(path)


@contextlib.contextmanager
def _install_lock(path: Path) -> Iterator[None]:
    lock_path = path.parent / f".{path.name}.install.lock"
    lock_path.parent.mkdir(mode=0o755, parents=True, exist_ok=True)
    with lock_path.open("a+") as handle:
        try:
            fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as error:
            raise InstallerError(f"Cannot install {path!r}; another installer already holds {lock_path!r}") from error
        yield


def _running_processes(distributions: Sequence[Path]) -> list[int]:
    found: list[int] = []
    proc = Path("/proc")
    for candidate in proc.iterdir() if proc.is_dir() else ():
        if not candidate.name.isdigit() or int(candidate.name) == os.getpid():
            continue
        try:
            command = (candidate / "cmdline").read_bytes().replace(b"\0", b" ").decode(errors="replace")
        except OSError:
            continue
        if any(str(path) in command for path in distributions):
            found.append(int(candidate.name))
    return found


def _check_not_running(plan: InstallPlan) -> None:
    watched = (plan.options.install_dir, *plan.options.retired)
    processes = _running_processes(watched)
    if processes:
        raise InstallerError(f"Cofrinho processes {processes!r} use a distribution being replaced; close them and retry")


def _smoke(launcher: Path) -> None:
    environment = os.environ.copy()
    environment["JAVA_HOME"] = "/missing-system-java"
    try:
        subprocess.run([str(launcher), "--help"], check=True, capture_output=True, text=True, timeout=MAX_SMOKE_SECONDS, env=environment)
    except (OSError, subprocess.SubprocessError) as error:
        raise InstallerError(f"Launcher {launcher!r} failed the --help smoke test: {error}") from error


def _path_state(plan: InstallPlan, stage: Path, rollback: Path) -> dict[str, object]:
    return {"phase": "staging", "install": str(plan.options.install_dir), "stage": str(stage), "rollback": str(rollback), "link": str(plan.options.bin_dir / "cofrinho"), "previous_link": plan.previous_link, "cleanup": [str(rollback), *(str(path) for path in plan.options.retired)], "had_old": plan.options.install_dir.exists()}


def _replace_link(path: Path, launcher: Path) -> None:
    path.parent.mkdir(mode=0o755, parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.new-{os.getpid()}")
    temporary.unlink(missing_ok=True)
    temporary.symlink_to(launcher)
    os.replace(temporary, path)


def _activation(plan: InstallPlan, stage: Path, state_file: Path) -> None:
    install = plan.options.install_dir
    rollback = install.with_name(f".{install.name}.rollback-{os.getpid()}")
    state = _path_state(plan, stage, rollback)
    _write_state(state_file, state)
    try:
        if install.exists():
            os.replace(install, rollback)
        state["phase"] = "old-renamed"
        _write_state(state_file, state)
        os.replace(stage, install)
        state["phase"] = "new-activated"
        _write_state(state_file, state)
        _replace_link(plan.options.bin_dir / "cofrinho", install / "bin" / "cofrinho")
        state["phase"] = "link-switched"
        _write_state(state_file, state)
        _smoke(install / "bin" / "cofrinho")
    except Exception:
        _rollback_activation(plan, state_file, state, stage, rollback)
        raise
    _cleanup_after_activation(plan, state_file, state)


def _rollback_activation(plan: InstallPlan, state_file: Path, state: dict[str, object], stage: Path, rollback: Path) -> None:
    install = plan.options.install_dir
    try:
        _restore_link(plan.options.bin_dir / "cofrinho", plan.previous_link)
        if bool(state.get("had_old")) and not rollback.exists():
            _remove_tree(stage)
            _remove_state(state_file)
            return
        if install.exists():
            _remove_tree(install)
        if rollback.exists():
            os.replace(rollback, install)
        _remove_tree(stage)
        _remove_state(state_file)
    except OSError as error:
        _write_state(state_file, {**state, "phase": "old-renamed"})
        raise InstallerError(f"Activation failed and rollback needs manual recovery at {rollback!r}: {error}") from error


def _cleanup_after_activation(plan: InstallPlan, state_file: Path, state: dict[str, object]) -> None:
    failures: list[Path] = []
    for value in state["cleanup"]:
        try:
            _remove_tree(Path(str(value)))
        except OSError:
            failures.append(Path(str(value)))
    if failures:
        state["phase"] = "cleanup-pending"
        state["cleanup"] = [str(path) for path in failures]
        _write_state(state_file, state)
        raise CleanupError(f"Installed successfully, but old program paths remain: {failures}")
    _remove_state(state_file)


def _stage(plan: InstallPlan, state_file: Path) -> Path:
    stage = Path(tempfile.mkdtemp(prefix=f".{plan.options.install_dir.name}.staging-", dir=plan.options.install_dir.parent))
    rollback = plan.options.install_dir.with_name(f".{plan.options.install_dir.name}.rollback-{os.getpid()}")
    _write_state(state_file, _path_state(plan, stage, rollback))
    try:
        _extract_archive(plan.options.archive, plan.members, stage)
        marker = {"installer": "cofrinho", "version": plan.version, "sha256": plan.archive_digest}
        (stage / ".cofrinho-install.json").write_text(json.dumps(marker, sort_keys=True) + "\n", encoding="utf-8")
        return stage
    except Exception:
        _remove_tree(stage)
        _remove_state(state_file)
        raise


def _path_notice(plan: InstallPlan) -> str | None:
    entries = [Path(entry or ".").absolute() for entry in os.environ.get("PATH", "").split(os.pathsep)]
    if plan.options.bin_dir not in entries:
        return f"Add {plan.options.bin_dir} to PATH, for example: export PATH={plan.options.bin_dir}:$PATH"
    found = shutil.which("cofrinho")
    command = plan.options.bin_dir / "cofrinho"
    if found and _resolved(Path(found)) != _resolved(command):
        return f"Warning: {found} appears earlier in PATH; use {command} explicitly"
    return None


def _print_dry_run(plan: InstallPlan) -> None:
    print(f"Would install {plan.version} ({plan.archive_digest}) into {plan.options.install_dir}")
    print(f"Would set command {plan.options.bin_dir / 'cofrinho'} -> {plan.options.install_dir / 'bin' / 'cofrinho'}")
    if plan.options.install_dir.exists():
        print(f"Would replace existing program tree {plan.options.install_dir}")
    for path in plan.options.retired:
        print(f"Would retire validated program tree {path}")


def install(options: InstallOptions) -> None:
    """Install one archive. Example: install(InstallOptions(...))."""
    initial = _validate_paths(options)
    if initial.dry_run:
        _print_dry_run(_make_plan(initial))
        return
    state_file = _state_path(initial.install_dir)
    initial.install_dir.parent.mkdir(mode=0o755, parents=True, exist_ok=True)
    with _install_lock(initial.install_dir):
        _recover_state(state_file)
        _check_unexplained_artifacts(initial.install_dir)
        plan = _make_plan(initial)
        _check_not_running(plan)
        stage = _stage(plan, state_file)
        _activation(plan, stage, state_file)
        print(f"Installed {plan.version} ({plan.archive_digest}) at {plan.options.install_dir}")
        print(f"Command: {plan.options.bin_dir / 'cofrinho'}")
        print("Ledger, SQLite companions, backups, exports, and transfer state were not accessed or changed.")
        notice = _path_notice(plan)
        if notice:
            print(notice, file=sys.stderr)


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Install a downloaded Cofrinho Linux x86_64 CLI without touching ledgers.")
    parser.add_argument("archive", nargs="?", type=Path, help="release .tar.gz archive")
    parser.add_argument("--checksum", type=Path, help="checksum file; default is ARCHIVE.sha256")
    parser.add_argument("--install-dir", type=Path, default=Path.home() / ".local" / "opt" / "cofrinho")
    parser.add_argument("--bin-dir", type=Path, default=Path.home() / ".local" / "bin")
    parser.add_argument("--retire-install", action="append", type=Path, default=[], help="exact old distribution path to remove after verification")
    parser.add_argument("--dry-run", action="store_true", help="validate and print changes without changing files or running the candidate")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    """Run the installer. Example: main(["release.tar.gz"])."""
    parser = _parser()
    arguments = parser.parse_args(argv)
    if arguments.archive is None:
        parser.error("the following arguments are required: archive")
    try:
        install(InstallOptions(arguments.archive, arguments.checksum, arguments.install_dir, arguments.bin_dir, tuple(arguments.retire_install), arguments.dry_run))
    except CleanupError as error:
        print(f"cofrinho installer: {error}", file=sys.stderr)
        return 2
    except (InstallerError, OSError, tarfile.TarError) as error:
        print(f"cofrinho installer: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
