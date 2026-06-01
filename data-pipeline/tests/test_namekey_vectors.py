"""Parity guard: the shared NameKey vectors must hold for the Python reference impl, and the
canonical fixture must stay byte-identical to the Android mirror (Stage 1, spec §7.1)."""
import json
import pathlib

from bgtiers.localize import normalize_name_key

_CANONICAL = pathlib.Path(__file__).parent / "fixtures" / "namekey_vectors.json"
_MIRROR = (pathlib.Path(__file__).resolve().parents[2]
           / "android/overlay-app/app/src/test/resources/namekey_vectors.json")


def test_shared_vectors_hold_for_reference():
    for v in json.loads(_CANONICAL.read_text("utf-8")):
        assert normalize_name_key(v["in"]) == v["out"], v["in"]


def test_canonical_and_android_mirror_in_sync():
    assert _CANONICAL.read_bytes() == _MIRROR.read_bytes(), \
        "namekey vectors drifted between canonical and android mirror"
