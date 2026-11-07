#!/usr/bin/env python3
"""Drive the packaged Cofrinho TUI through a real pseudo-terminal."""

from __future__ import annotations

import argparse
import fcntl
import os
from pathlib import Path
import pty
import select
import struct
import sys
import termios
import time


_TIMEOUT_SECONDS: float = 15.0
_WINDOW_ROWS: int = 24
_WINDOW_COLUMNS: int = 80


def _arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Smoke-test the packaged Cofrinho TUI.")
    parser.add_argument("cli", type=Path, help="path to the packaged cofrinho launcher")
    parser.add_argument("database", type=Path, help="temporary SQLite ledger path")
    return parser.parse_args()


def _environment() -> dict[str, str]:
    environment = os.environ.copy()
    environment["TERM"] = "xterm-256color"
    environment["JAVA_HOME"] = "/missing-system-java"
    return environment


def _set_window_size(descriptor: int) -> None:
    size = struct.pack("HHHH", _WINDOW_ROWS, _WINDOW_COLUMNS, 0, 0)
    fcntl.ioctl(descriptor, termios.TIOCSWINSZ, size)


def _read_output(descriptor: int) -> bytes:
    try:
        return os.read(descriptor, 4096)
    except OSError:
        return b""


def _invoke(cli: Path, database: Path, keys: bytes) -> bytes:
    process, descriptor = pty.fork()
    if process == 0:
        os.execve(str(cli), [str(cli), "--db", str(database)], _environment())
    _set_window_size(descriptor)
    os.set_blocking(descriptor, False)
    os.write(descriptor, keys)
    return _wait_for_exit(process, descriptor)


def _wait_for_exit(process: int, descriptor: int) -> bytes:
    output = bytearray()
    query_buffer = bytearray()
    deadline = time.monotonic() + _TIMEOUT_SECONDS
    while time.monotonic() < deadline:
        _read_output_when_ready(descriptor, output, query_buffer)
        result, status = os.waitpid(process, os.WNOHANG)
        if result:
            _read_output_when_ready(descriptor, output, query_buffer)
            os.close(descriptor)
            _require_success(status, bytes(output))
            return bytes(output)
        time.sleep(0.05)
    os.kill(process, 9)
    os.waitpid(process, 0)
    os.close(descriptor)
    raise RuntimeError(f"TUI process did not exit within {_TIMEOUT_SECONDS:g} seconds")


def _read_output_when_ready(descriptor: int, output: bytearray, query_buffer: bytearray) -> None:
    ready, _, _ = select.select([descriptor], [], [], 0.05)
    if ready:
        chunk = _read_output(descriptor)
        output.extend(chunk)
        query_buffer.extend(chunk)
        _answer_cursor_queries(descriptor, query_buffer)


def _answer_cursor_queries(descriptor: int, query_buffer: bytearray) -> None:
    query = b"\x1b[6n"
    while query in query_buffer:
        query_buffer[:] = query_buffer[query_buffer.index(query) + len(query):]
        os.write(descriptor, b"\x1b[24;1R")
    if len(query_buffer) > len(query):
        del query_buffer[:-len(query)]


def _require_success(status: int, output: bytes) -> None:
    exit_code = os.waitstatus_to_exitcode(status)
    if exit_code != 0:
        rendered = output.decode(errors="replace")[-1000:]
        raise RuntimeError(f"TUI exited with status {exit_code}: {rendered}")
    if b"StaticLoggerBinder" in output or b"SLF4J" in output:
        raise RuntimeError("TUI emitted an SLF4J binding warning")


def main() -> int:
    arguments = _arguments()
    _invoke(arguments.cli, arguments.database, b"q")
    _invoke(arguments.cli, arguments.database, b"n10\t\t\t\t\nq")
    print("Packaged TUI PTY smoke test passed")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, RuntimeError) as error:
        print(f"TUI smoke test failed: {error}", file=sys.stderr)
        sys.exit(1)
