from __future__ import annotations

import shlex
import shutil
import subprocess
import tempfile
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import Iterator


@dataclass(frozen=True)
class DwgConvertResult:
    dxf_path: Path
    converter: str
    temporary: bool


@contextmanager
def convert_dwg_to_dxf(
    input_path: Path,
    converter_command: str | None = None,
    timeout_seconds: int = 120,
) -> Iterator[DwgConvertResult]:
    input_path = input_path.resolve()
    with tempfile.TemporaryDirectory(prefix="cad-dwg-") as temp_dir:
        output_path = Path(temp_dir) / f"{input_path.stem}.dxf"
        command, converter_name = _build_command(input_path, output_path, converter_command)
        result = subprocess.run(
            command,
            capture_output=True,
            text=True,
            timeout=timeout_seconds,
            check=False,
        )
        if result.returncode != 0:
            stderr = (result.stderr or result.stdout or "").strip()
            raise RuntimeError(f"DWG 转 DXF 失败: {stderr}")
        if not output_path.exists():
            raise RuntimeError(f"DWG 转换完成但未生成 DXF: {output_path}")
        yield DwgConvertResult(output_path, converter_name, temporary=True)


def _build_command(
    input_path: Path,
    output_path: Path,
    converter_command: str | None,
) -> tuple[list[str], str]:
    if converter_command:
        command_text = converter_command.replace("{input}", str(input_path)).replace("{output}", str(output_path))
        if "{input}" not in converter_command and "{output}" not in converter_command:
            command_text = f"{command_text} {input_path} {output_path}"
        command = shlex.split(command_text, posix=False)
        return command, command[0]

    dwg2dxf = shutil.which("dwg2dxf")
    if not dwg2dxf:
        raise RuntimeError(
            "当前环境未找到 dwg2dxf。请安装 LibreDWG，或用 --dwg-converter 传入转换命令模板。"
        )
    return [dwg2dxf, "-o", str(output_path), str(input_path)], dwg2dxf
