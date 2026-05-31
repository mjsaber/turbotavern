"""Global process run-lock via flock (spec §4.3). Non-blocking: fail fast if held."""
from __future__ import annotations
import contextlib
import fcntl
import os


class AlreadyRunning(Exception):
    pass


@contextlib.contextmanager
def run_lock(path: str):
    fd = os.open(path, os.O_CREAT | os.O_RDWR, 0o644)
    try:
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as exc:
            raise AlreadyRunning(f"another fetch-stats run holds {path}") from exc
        try:
            yield
        finally:
            fcntl.flock(fd, fcntl.LOCK_UN)
    finally:
        os.close(fd)
