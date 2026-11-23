"""canonical JSON bytes, 내용 hash, 시각 표기"""

import hashlib
import json
import unicodedata
from datetime import UTC, datetime

type JsonValue = None | bool | int | float | str | list[JsonValue] | dict[str, JsonValue]


def canonical_bytes(value: JsonValue) -> bytes:
    """key 정렬, 공백 없음, 문자열 값 NFC, UTF-8"""

    return (
        json
        .dumps(
            _nfc(value),
            sort_keys=True,
            ensure_ascii=False,
            separators=(",", ":"),
            allow_nan=False,
        )
        .encode("utf-8")
    )


def content_hash(body: bytes) -> str:
    return "sha256:" + (
        hashlib
        .sha256(body)
        .hexdigest()
    )


def utc_text(moment: datetime) -> str:
    """UTC 초 단위 정규형 YYYY-MM-DDTHH:MM:SSZ"""

    return (
        moment
        .astimezone(UTC)
        .strftime("%Y-%m-%dT%H:%M:%SZ")
    )


def _nfc(value: JsonValue) -> JsonValue:
    if isinstance(value, str):
        return unicodedata.normalize("NFC", value)

    if isinstance(value, list):
        return [_nfc(item) for item in value]

    if isinstance(value, dict):
        return {key: _nfc(item) for key, item in value.items()}

    return value
