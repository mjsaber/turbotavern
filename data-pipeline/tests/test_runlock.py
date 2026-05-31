import pytest
from bgtiers import runlock


def test_lock_acquired_and_released(tmp_path):
    p = tmp_path / ".fetch.lock"
    with runlock.run_lock(str(p)):
        assert p.exists()


def test_second_lock_fails_while_held(tmp_path):
    p = tmp_path / ".fetch.lock"
    with runlock.run_lock(str(p)):
        with pytest.raises(runlock.AlreadyRunning):
            with runlock.run_lock(str(p)):
                pass
