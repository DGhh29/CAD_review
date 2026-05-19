from __future__ import annotations

import json
from typing import Any


def dumps_json(data: Any, pretty: bool = False) -> bytes:
    data = _sanitize_json(data)
    try:
        import orjson

        option = orjson.OPT_SERIALIZE_NUMPY
        if pretty:
            option |= orjson.OPT_INDENT_2
        return orjson.dumps(data, option=option)
    except Exception:
        text = json.dumps(data, ensure_ascii=False, indent=2 if pretty else None)
        return text.encode("utf-8")


def _sanitize_json(value: Any) -> Any:
    if isinstance(value, str):
        return value.encode("utf-8", "replace").decode("utf-8")
    if isinstance(value, dict):
        return {_sanitize_json(key): _sanitize_json(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_sanitize_json(item) for item in value]
    return value
