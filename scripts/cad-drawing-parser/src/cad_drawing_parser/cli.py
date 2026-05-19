from __future__ import annotations

import argparse
import sys
from pathlib import Path

from cad_drawing_parser.core import ParseConfig, parse_cad_file
from cad_drawing_parser.jsonio import dumps_json


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description="解析 DWG/DXF CAD 图纸为结构化 JSON")
    parser.add_argument("input", help="输入 DWG 或 DXF 文件路径")
    parser.add_argument("-o", "--output", help="输出 JSON 文件路径；不传则输出到 stdout")
    parser.add_argument("--pretty", action="store_true", help="格式化输出 JSON")
    parser.add_argument("--max-entities", type=int, default=10000, help="最多输出多少个图元记录")
    parser.add_argument("--max-texts", type=int, default=2000, help="最多输出多少条文字/尺寸样本")
    parser.add_argument("--max-blocks", type=int, default=1000, help="最多输出多少个块定义摘要")
    parser.add_argument("--no-entities", action="store_true", help="只输出摘要，不输出 entities 明细")
    parser.add_argument("--no-blocks", action="store_true", help="不输出 blocks 摘要")
    parser.add_argument(
        "--dwg-converter",
        help='DWG 转 DXF 命令模板，例如 "dwg2dxf -o {output} {input}"',
    )
    parser.add_argument("--dwg-timeout", type=int, default=120, help="DWG 转换超时时间，单位秒")
    args = parser.parse_args(argv)

    config = ParseConfig(
        max_entities=args.max_entities,
        max_texts=args.max_texts,
        max_blocks=args.max_blocks,
        include_entities=not args.no_entities,
        include_blocks=not args.no_blocks,
        dwg_converter=args.dwg_converter,
        dwg_timeout_seconds=args.dwg_timeout,
    )

    try:
        result = parse_cad_file(args.input, config)
        payload = dumps_json(result, pretty=args.pretty)
        if args.output:
            Path(args.output).write_bytes(payload)
        else:
            sys.stdout.buffer.write(payload)
            sys.stdout.buffer.write(b"\n")
    except Exception as exc:
        error = {
            "success": False,
            "error_type": exc.__class__.__name__,
            "error_message": str(exc),
        }
        sys.stderr.buffer.write(dumps_json(error, pretty=True))
        sys.stderr.buffer.write(b"\n")
        raise SystemExit(1) from exc


if __name__ == "__main__":
    main()
