"""名称归一化契约：检索键写入与（未来）检索查询必须共用此函数。"""
from __future__ import annotations
import unicodedata


def normalize_name_key(name) -> str:
    """NFKC -> casefold -> trim -> 内部空白折叠为单空格。
    非字符串 / 空 / 纯空白 -> 空字符串（调用方据此跳过或删除该行）。"""
    if not isinstance(name, str):
        return ""
    text = unicodedata.normalize("NFKC", name).casefold()
    # Drop lone surrogates: they cannot UTF-8-encode and would crash the SQLite
    # write. "ignore" strips them while keeping valid non-BMP scalars intact.
    text = text.encode("utf-8", "ignore").decode("utf-8")
    return " ".join(text.split())
