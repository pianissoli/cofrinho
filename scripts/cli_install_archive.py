"""Safe archive extraction and installed-distribution validation for Cofrinho."""

from __future__ import annotations

import dataclasses
import json
import os
from pathlib import Path, PurePosixPath
import posixpath
import re
import shutil
import tarfile
from typing import Iterator, NoReturn, Sequence


PACKAGE_ROOT = "cofrinho-linux-x86_64"
MAX_MEMBERS = 100_000
MAX_ARCHIVE_BYTES = 2 * 1024 * 1024 * 1024


class InstallerError(RuntimeError):
    """A user-actionable installer failure."""


@dataclasses.dataclass(frozen=True)
class ArchiveMember:
    info: tarfile.TarInfo
    relative: PurePosixPath


def _error(value: object, expected: str) -> NoReturn:
    raise InstallerError(f"Invalid {value!r}; expected {expected}")


def _inside(path: Path, parent: Path) -> bool:
    try:
        path.relative_to(parent)
    except ValueError:
        return False
    return True


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


def _ensure_parent_without_symlink(path: Path, root: Path) -> None:
    parent = path.parent
    while parent != root:
        if parent.exists() and parent.is_symlink():
            _error(parent, "a non-symlink extraction parent")
        parent = parent.parent


def _write_regular(source: tarfile.ExFileObject, destination: Path, mode: int) -> None:
    with destination.open("xb") as output:
        shutil.copyfileobj(source, output)
    destination.chmod(mode & 0o777)


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
                with extracted:
                    _write_regular(extracted, output, member.info.mode)
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
    for entry in _walk_distribution(root):
        if entry.is_symlink():
            _safe_link_target(entry, root)
    _reject_financial_entries(_walk_distribution(root))
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
    if not (root / "lib").is_dir() or not any((root / "lib").glob("*.jar")):
        _error(root / "lib", "application and dependency JARs")
    _validate_program_directories(root)
    _application_jar(launcher, root / "lib")
