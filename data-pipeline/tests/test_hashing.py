from bgtiers.hashing import content_hash
from bgtiers.models import NormalizedRow, NormalizedFeed


def _feed(rows, patch="27.0", fp=None):
    return NormalizedFeed(rows=rows, patch=patch, schema_fingerprint=fp or ["avg", "card_id"])


def test_hash_is_stable_regardless_of_row_order():
    a = _feed([NormalizedRow("A", 4.1, 100), NormalizedRow("B", 3.9, 200)])
    b = _feed([NormalizedRow("B", 3.9, 200), NormalizedRow("A", 4.1, 100)])
    assert content_hash(a) == content_hash(b)


def test_hash_changes_when_stat_changes():
    assert content_hash(_feed([NormalizedRow("A", 4.1, 100)])) != \
           content_hash(_feed([NormalizedRow("A", 4.2, 100)]))


def test_hash_changes_when_patch_changes():
    assert content_hash(_feed([NormalizedRow("A", 4.1, 100)], patch="27.0")) != \
           content_hash(_feed([NormalizedRow("A", 4.1, 100)], patch="27.2"))


def test_hash_changes_when_schema_fingerprint_changes():
    assert content_hash(_feed([NormalizedRow("A", 4.1, 100)], fp=["a", "b"])) != \
           content_hash(_feed([NormalizedRow("A", 4.1, 100)], fp=["a", "b", "c"]))


def test_float_rounding_ignores_sub_micro_noise():
    assert content_hash(_feed([NormalizedRow("A", 4.1000001, 100)])) == \
           content_hash(_feed([NormalizedRow("A", 4.1000002, 100)]))
