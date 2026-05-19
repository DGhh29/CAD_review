from __future__ import annotations

import hashlib
import math
import time
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from cad_drawing_parser.dwg import convert_dwg_to_dxf


SEMANTIC_KEYWORDS = {
    "walls": ("wall", "a-wall", "墙", "墙体", "剪力墙", "partition"),
    "doors": ("door", "门", "门窗"),
    "windows": ("window", "窗", "门窗"),
    "columns": ("column", "pillar", "柱", "结构柱"),
    "stairs": ("stair", "楼梯", "梯段"),
    "axes": ("axis", "grid", "轴线", "轴网"),
    "dimensions": ("dim", "dimension", "标注", "尺寸"),
    "parking": ("parking", "车位", "停车"),
    "fire": ("fire", "消防", "消火栓", "喷淋", "疏散"),
    "rooms": ("room", "房间", "户型", "空间"),
}


@dataclass(frozen=True)
class ParseConfig:
    max_entities: int = 10000
    max_texts: int = 2000
    max_blocks: int = 1000
    include_entities: bool = True
    include_blocks: bool = True
    include_layer_entities: bool = False
    dwg_converter: str | None = None
    dwg_timeout_seconds: int = 120


def parse_cad_file(input_path: str | Path, config: ParseConfig | None = None) -> dict[str, Any]:
    config = config or ParseConfig()
    path = Path(input_path).resolve()
    if not path.exists():
        raise FileNotFoundError(f"文件不存在: {path}")

    ext = path.suffix.lower()
    if ext == ".dxf":
        return _parse_dxf(path, source_path=path, config=config, dwg_conversion=None)
    if ext == ".dwg":
        with convert_dwg_to_dxf(path, config.dwg_converter, config.dwg_timeout_seconds) as converted:
            return _parse_dxf(
                converted.dxf_path,
                source_path=path,
                config=config,
                dwg_conversion={"converter": converted.converter, "temporary": converted.temporary},
            )
    raise ValueError(f"不支持的文件类型: {ext}，当前仅支持 .dxf 和 .dwg")


def _parse_dxf(
    dxf_path: Path,
    source_path: Path,
    config: ParseConfig,
    dwg_conversion: dict[str, Any] | None,
) -> dict[str, Any]:
    started = time.perf_counter()
    try:
        import ezdxf
        from ezdxf import recover
    except ImportError as exc:
        raise RuntimeError("缺少依赖 ezdxf，请先执行 pip install -r requirements.txt") from exc

    warnings: list[str] = []
    try:
        doc = ezdxf.readfile(dxf_path)
    except ezdxf.DXFStructureError:
        doc, auditor = recover.readfile(dxf_path)
        warnings.extend(str(error) for error in auditor.errors[:20])

    modelspace = doc.modelspace()
    layer_counter: Counter[str] = Counter()
    type_counter: Counter[str] = Counter()
    semantic_counter: Counter[str] = Counter()
    block_counter: Counter[str] = Counter()
    entities: list[dict[str, Any]] = []
    texts: list[dict[str, Any]] = []
    dimensions: list[dict[str, Any]] = []
    global_bbox = Bounds()
    entity_count = 0

    for entity in modelspace:
        entity_count += 1
        entity_type = entity.dxftype()
        layer = _dxf_attr(entity, "layer", "0")
        type_counter[entity_type] += 1
        layer_counter[layer] += 1

        record, bbox = _extract_entity(entity, entity_count)
        semantic = _classify_semantic(record)
        if semantic:
            semantic_counter[semantic] += 1
            record["semantic"] = semantic
        if bbox.valid:
            record["bbox"] = bbox.to_dict()
            global_bbox.include_bounds(bbox)
        if record.get("text") and len(texts) < config.max_texts:
            texts.append(
                {
                    "text": record["text"],
                    "layer": layer,
                    "type": entity_type,
                    "point": record.get("point") or record.get("insert"),
                }
            )
        if entity_type == "DIMENSION" and len(dimensions) < config.max_texts:
            dimensions.append(_extract_dimension(entity, record))
        if entity_type == "INSERT" and record.get("block"):
            block_counter[str(record["block"])] += 1
        if config.include_entities and len(entities) < config.max_entities:
            entities.append(record)

    layers = _extract_layers(doc, layer_counter)
    blocks = _extract_blocks(doc, block_counter, config) if config.include_blocks else []
    elapsed_ms = int((time.perf_counter() - started) * 1000)
    source_sha256 = _sha256_file(source_path)
    top_layers = layer_counter.most_common(30)

    result = {
        "schema_version": "cad-drawing-parser.v1",
        "success": True,
        "source": {
            "path": str(source_path),
            "file_name": source_path.name,
            "extension": source_path.suffix.lower(),
            "size_bytes": source_path.stat().st_size,
            "sha256": source_sha256,
            "parsed_as": "dxf",
            "dwg_conversion": dwg_conversion,
        },
        "metadata": {
            "dxf_version": getattr(doc, "dxfversion", None),
            "acad_release": getattr(doc, "acad_release", None),
            "units": _extract_units(doc),
        },
        "summary": {
            "entity_count": entity_count,
            "captured_entity_count": len(entities),
            "entity_truncated": entity_count > len(entities),
            "layer_count": len(layers),
            "block_count": len(blocks),
            "text_count": len(texts),
            "dimension_count": len(dimensions),
            "duration_ms": elapsed_ms,
            "bbox": global_bbox.to_dict() if global_bbox.valid else None,
        },
        "statistics": {
            "by_type": dict(type_counter.most_common()),
            "by_layer": dict(top_layers),
        },
        "semantic": {
            "counts": dict(semantic_counter),
            "rules": {key: list(value) for key, value in SEMANTIC_KEYWORDS.items()},
        },
        "layers": layers,
        "blocks": blocks,
        "texts": texts,
        "dimensions": dimensions,
        "entities": entities,
        "audit_pack": _build_audit_pack(
            entity_count=entity_count,
            type_counter=type_counter,
            layer_counter=layer_counter,
            semantic_counter=semantic_counter,
            texts=texts,
            dimensions=dimensions,
            bbox=global_bbox,
            warnings=warnings,
        ),
        "warnings": warnings,
    }
    return result


def _extract_entity(entity: Any, index: int) -> tuple[dict[str, Any], "Bounds"]:
    entity_type = entity.dxftype()
    layer = _dxf_attr(entity, "layer", "0")
    record: dict[str, Any] = {
        "index": index,
        "type": entity_type,
        "layer": layer,
        "handle": getattr(entity.dxf, "handle", None),
    }
    bbox = Bounds()

    if entity_type == "LINE":
        start = _point(_dxf_attr(entity, "start", None))
        end = _point(_dxf_attr(entity, "end", None))
        record.update({"start": start, "end": end, "length": _distance(start, end)})
        bbox.include(start)
        bbox.include(end)
    elif entity_type == "LWPOLYLINE":
        points = [[_round(p[0]), _round(p[1])] for p in entity.get_points("xy")]
        record.update({"points": points, "closed": bool(entity.closed), "length": _polyline_length(points, bool(entity.closed))})
        for point in points:
            bbox.include(point)
    elif entity_type == "POLYLINE":
        points = [_point(vertex.dxf.location) for vertex in entity.vertices]
        record.update({"points": points, "closed": bool(entity.is_closed), "length": _polyline_length(points, bool(entity.is_closed))})
        for point in points:
            bbox.include(point)
    elif entity_type == "CIRCLE":
        center = _point(_dxf_attr(entity, "center", None))
        radius = _round(_dxf_attr(entity, "radius", 0.0))
        record.update({"center": center, "radius": radius})
        bbox.include([center[0] - radius, center[1] - radius])
        bbox.include([center[0] + radius, center[1] + radius])
    elif entity_type == "ARC":
        center = _point(_dxf_attr(entity, "center", None))
        radius = _round(_dxf_attr(entity, "radius", 0.0))
        start_angle = _round(_dxf_attr(entity, "start_angle", 0.0))
        end_angle = _round(_dxf_attr(entity, "end_angle", 0.0))
        record.update({"center": center, "radius": radius, "start_angle": start_angle, "end_angle": end_angle})
        bbox.include([center[0] - radius, center[1] - radius])
        bbox.include([center[0] + radius, center[1] + radius])
    elif entity_type in {"TEXT", "MTEXT"}:
        text = entity.plain_text() if entity_type == "MTEXT" else _dxf_attr(entity, "text", "")
        point = _point(_dxf_attr(entity, "insert", None))
        record.update({"text": _clean_text(text), "insert": point, "height": _round(_dxf_attr(entity, "height", 0.0))})
        bbox.include(point)
    elif entity_type == "INSERT":
        insert = _point(_dxf_attr(entity, "insert", None))
        record.update(
            {
                "block": _dxf_attr(entity, "name", None),
                "insert": insert,
                "rotation": _round(_dxf_attr(entity, "rotation", 0.0)),
                "scale": [
                    _round(_dxf_attr(entity, "xscale", 1.0)),
                    _round(_dxf_attr(entity, "yscale", 1.0)),
                    _round(_dxf_attr(entity, "zscale", 1.0)),
                ],
            }
        )
        bbox.include(insert)
    elif entity_type == "DIMENSION":
        point = _point(_dxf_attr(entity, "defpoint", None))
        record.update({"point": point, "measurement": _safe_measurement(entity)})
        bbox.include(point)
    elif entity_type == "HATCH":
        record.update({"path_count": len(getattr(entity.paths, "paths", []))})
    elif entity_type == "SPLINE":
        control_points = list(getattr(entity, "control_points", []) or [])
        record.update({"degree": _dxf_attr(entity, "degree", None), "control_point_count": len(control_points)})
    else:
        point = _point(_dxf_attr(entity, "insert", None) or _dxf_attr(entity, "location", None))
        if point:
            record["point"] = point
            bbox.include(point)

    return record, bbox


def _extract_dimension(entity: Any, record: dict[str, Any]) -> dict[str, Any]:
    return {
        "layer": record.get("layer"),
        "handle": record.get("handle"),
        "measurement": record.get("measurement"),
        "text": _clean_text(_dxf_attr(entity, "text", "")),
        "point": record.get("point"),
    }


def _extract_layers(doc: Any, layer_counter: Counter[str]) -> list[dict[str, Any]]:
    layers = []
    for layer in doc.layers:
        name = layer.dxf.name
        layers.append(
            {
                "name": name,
                "color": _dxf_attr(layer, "color", None),
                "linetype": _dxf_attr(layer, "linetype", None),
                "is_off": _safe_call(layer, "is_off", False),
                "is_frozen": _safe_call(layer, "is_frozen", False),
                "is_locked": _safe_call(layer, "is_locked", False),
                "entity_count": layer_counter.get(name, 0),
                "semantic": _classify_text(name),
            }
        )
    return sorted(layers, key=lambda item: item["entity_count"], reverse=True)


def _extract_blocks(doc: Any, block_counter: Counter[str], config: ParseConfig) -> list[dict[str, Any]]:
    blocks = []
    for block in doc.blocks:
        if len(blocks) >= config.max_blocks:
            break
        try:
            entity_count = sum(1 for _ in block)
        except Exception:
            entity_count = None
        blocks.append(
            {
                "name": block.name,
                "entity_count": entity_count,
                "insert_count": block_counter.get(block.name, 0),
                "semantic": _classify_text(block.name),
            }
        )
    return blocks


def _extract_units(doc: Any) -> dict[str, Any]:
    header = doc.header
    return {
        "insunits": header.get("$INSUNITS"),
        "measurement": header.get("$MEASUREMENT"),
        "lunits": header.get("$LUNITS"),
        "dimlfac": header.get("$DIMLFAC"),
    }


def _build_audit_pack(
    entity_count: int,
    type_counter: Counter[str],
    layer_counter: Counter[str],
    semantic_counter: Counter[str],
    texts: list[dict[str, Any]],
    dimensions: list[dict[str, Any]],
    bbox: "Bounds",
    warnings: list[str],
) -> dict[str, Any]:
    return {
        "purpose": "给规则引擎或大模型审核使用的精简包，不建议把全量 entities 直接塞给模型。",
        "drawing_overview": {
            "entity_count": entity_count,
            "top_entity_types": type_counter.most_common(20),
            "top_layers": layer_counter.most_common(20),
            "semantic_counts": dict(semantic_counter),
            "bbox": bbox.to_dict() if bbox.valid else None,
        },
        "review_clues": {
            "dimension_samples": dimensions[:80],
            "text_samples": texts[:120],
            "warnings": warnings,
        },
        "model_prompt_hint": (
            "先根据 semantic_counts、top_layers、dimension_samples 判断图纸内容覆盖范围；"
            "涉及楼距、间隙、净宽等合规判断时，应优先读取 DIMENSION 标注和几何距离，"
            "再结合规范规则库，不要只凭图层名称下结论。"
        ),
    }


def _classify_semantic(record: dict[str, Any]) -> str | None:
    joined = " ".join(
        str(record.get(key, ""))
        for key in ("layer", "block", "text", "type")
        if record.get(key) is not None
    )
    return _classify_text(joined)


def _classify_text(text: str | None) -> str | None:
    if not text:
        return None
    lowered = text.lower()
    for semantic, keywords in SEMANTIC_KEYWORDS.items():
        if any(keyword.lower() in lowered for keyword in keywords):
            return semantic
    return None


def _dxf_attr(entity: Any, name: str, default: Any = None) -> Any:
    try:
        return entity.dxf.get(name, default)
    except Exception:
        return default


def _safe_call(obj: Any, method_name: str, default: Any) -> Any:
    try:
        method = getattr(obj, method_name)
        return method()
    except Exception:
        return default


def _safe_measurement(entity: Any) -> float | None:
    try:
        return _round(entity.get_measurement())
    except Exception:
        return None


def _point(value: Any) -> list[float] | None:
    if value is None:
        return None
    try:
        return [_round(value[0]), _round(value[1]), _round(value[2] if len(value) > 2 else 0.0)]
    except Exception:
        try:
            return [_round(value.x), _round(value.y), _round(getattr(value, "z", 0.0))]
        except Exception:
            return None


def _distance(a: list[float] | None, b: list[float] | None) -> float | None:
    if not a or not b:
        return None
    return _round(math.dist(a[:2], b[:2]))


def _polyline_length(points: list[list[float]], closed: bool) -> float:
    if len(points) < 2:
        return 0.0
    total = 0.0
    for idx in range(1, len(points)):
        total += math.dist(points[idx - 1][:2], points[idx][:2])
    if closed:
        total += math.dist(points[-1][:2], points[0][:2])
    return _round(total)


def _round(value: Any) -> float:
    try:
        return round(float(value), 6)
    except Exception:
        return 0.0


def _clean_text(value: Any) -> str:
    text = "" if value is None else str(value)
    return " ".join(text.replace("\\P", " ").replace("\n", " ").split())


def _sha256_file(path: Path, chunk_size: int = 1024 * 1024) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as file:
        for chunk in iter(lambda: file.read(chunk_size), b""):
            digest.update(chunk)
    return digest.hexdigest()


class Bounds:
    def __init__(self) -> None:
        self.min_x = math.inf
        self.min_y = math.inf
        self.max_x = -math.inf
        self.max_y = -math.inf

    @property
    def valid(self) -> bool:
        return self.min_x <= self.max_x and self.min_y <= self.max_y

    def include(self, point: list[float] | None) -> None:
        if not point or len(point) < 2:
            return
        x, y = point[0], point[1]
        self.min_x = min(self.min_x, x)
        self.min_y = min(self.min_y, y)
        self.max_x = max(self.max_x, x)
        self.max_y = max(self.max_y, y)

    def include_bounds(self, other: "Bounds") -> None:
        if not other.valid:
            return
        self.include([other.min_x, other.min_y])
        self.include([other.max_x, other.max_y])

    def to_dict(self) -> dict[str, float]:
        return {
            "min_x": _round(self.min_x),
            "min_y": _round(self.min_y),
            "max_x": _round(self.max_x),
            "max_y": _round(self.max_y),
            "width": _round(self.max_x - self.min_x),
            "height": _round(self.max_y - self.min_y),
        }
