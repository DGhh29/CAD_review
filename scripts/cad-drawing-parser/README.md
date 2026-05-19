# CAD 图纸解析项目

这是一个独立的 CAD 图纸解析脚本项目，目标是把 DWG/DXF 图纸转成适合后续规则审核和大模型审核的结构化 JSON。

## 安装

```bash
cd scripts/cad-drawing-parser
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
```

如果要解析 DWG，还需要安装可用的 `dwg2dxf`，或者在运行时传入自定义转换命令。

## 使用

直接解析 DXF：

```bash
python parse_cad.py examples\minimal.dxf -o output.json --pretty
```

解析 DWG：

```bash
python parse_cad.py drawing.dwg -o output.json --pretty --dwg-converter "dwg2dxf -o {output} {input}"
```

限制输出体积：

```bash
python parse_cad.py drawing.dxf -o output.json --max-entities 3000 --max-texts 500
```

## 依赖

- `ezdxf` — DXF 解析
- `orjson` — 高性能 JSON 序列化（回退到标准库 json）
