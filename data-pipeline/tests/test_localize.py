from bgtiers.localize import normalize_name_key


def test_casefold_lowercases_latin():
    assert normalize_name_key("Sneed") == normalize_name_key("SNEED") == "sneed"


def test_trim_and_collapse_whitespace():
    assert normalize_name_key("  Rokara,   Arcane  Warrior ") == "rokara, arcane warrior"


def test_nfkc_fullwidth_to_halfwidth():
    # 全角拉丁/数字 -> 半角，再 casefold
    assert normalize_name_key("Ｓｎｅｅｄ") == "sneed"


def test_chinese_is_preserved():
    assert normalize_name_key("洛卡菈") == "洛卡菈"


def test_blank_inputs_return_empty():
    assert normalize_name_key("") == ""
    assert normalize_name_key("   ") == ""
    assert normalize_name_key("\t\n ") == ""


def test_non_string_returns_empty():
    assert normalize_name_key(None) == ""
    assert normalize_name_key(123) == ""


def test_punctuation_preserved():
    assert normalize_name_key("Al'Akir") == "al'akir"
