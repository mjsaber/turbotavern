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


def test_combining_equivalence():
    # NFKC unifies decomposed (E + U+0301 combining acute) vs precomposed (U+00C9)
    decomposed = "E" + chr(0x0301) + "clair"
    precomposed = chr(0x00C9) + "clair"
    assert decomposed != precomposed                       # genuinely distinct inputs
    assert normalize_name_key(decomposed) == normalize_name_key(precomposed) == "éclair"


def test_fullwidth_punctuation_normalized():
    # NFKC maps fullwidth comma (U+FF0C) to ASCII comma
    assert normalize_name_key("Ｆｏｏ" + chr(0xFF0C)) == "foo,"


def test_lone_surrogate_is_stripped():
    # lone surrogates cannot UTF-8-encode -> stripped so name_key is always storable
    assert normalize_name_key("a\ud800b") == "ab"
    assert normalize_name_key("\ud800") == ""
