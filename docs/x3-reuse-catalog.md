# x3-openfang CAD 能力复用清单（面向 CAD_review 项目）

> 整理日期：2026-05-20
> 源项目：`D:\workspace\oDev\x3-openfang`
> 目标项目：`D:\workspace\oDev\CAD_review`（Spring Boot + Spring AI 多 Agent 审图系统）

---

## 一、可复用 Skills（Python 微服务 / 独立进程）

x3-openfang 的 skills 目录下有 5 个 CAD 相关 Python skill，每个都是独立进程，通过 stdin/stdout JSON 协议通信。CAD_review 可以将它们作为 **sidecar 微服务** 或 **HTTP 子进程** 调用。

### 1. cad-dwg-converter

| 项目 | 说明 |
|------|------|
| 路径 | `x3-openfang/skills/cad-dwg-converter/src/converter.py` |
| 功能 | DWG → DXF 格式转换（基于 LibreDWG `dwg2dxf` CLI） |
| 工具名 | `cad_convert_dwg_to_dxf` |
| 输入 | `source_file`, `target_version`(R14~2018), `output_file`, `timeout_secs`, `use_cache` |
| 输出 | `{success, output_file, file_size, conversion_time_ms}` |
| 依赖 | LibreDWG 系统命令 |
| 缓存 | SHA256 文件哈希缓存，避免重复转换 |
| **复用方式** | CAD_review 当前 `CadParserService.parseDwg()` 直接发给 HTTP parser，可在前置加一步 DWG→DXF 转换，或将此 skill 包装为 HTTP endpoint 部署在 Docker 中 |

### 2. cad-dxf-parser

| 项目 | 说明 |
|------|------|
| 路径 | `x3-openfang/skills/cad-dxf-parser/src/parser.py` |
| 功能 | DXF 解析 + 建筑构件识别 |
| 工具名 | `cad_parse_dxf_file` / `cad_extract_building_elements` |
| 核心库 | `ezdxf` |
| 输出（parse） | `{entities[], layers[], blocks[], entity_count}` |
| 输出（elements） | `{elements: {wall[], door[], window[], column[], stair[]}, total_count}` |
| 识别逻辑 | 基于图层名关键词匹配（中英文：墙/wall, 门/door, 窗/window, 柱/column） |
| **复用方式** | 可直接替代或增强 CAD_review 的 `HttpParserClient`；也可作为独立 FastAPI 服务部署 |

### 3. cad-symbol-detector

| 项目 | 说明 |
|------|------|
| 路径 | `x3-openfang/skills/cad-symbol-detector/src/detector.py` |
| 功能 | 使用 YOLOv8 检测 CAD 图纸渲染图中的符号（门窗、家具、消防设施等） |
| 工具名 | `cad_detect_symbols_with_yolo` |
| 核心库 | `ultralytics`, `opencv-python`, `torch` |
| 输入 | `image_file`, `model_variant`(yolov8n~x), `confidence_threshold`, `use_gpu`, `model_path` |
| 输出 | `{detections[{class_name, confidence, bbox, bbox_normalized, center}], class_statistics, processing_time_ms}` |
| **复用方式** | CAD_review 目前缺少视觉检测能力。可将此 skill 部署为 GPU 微服务，Reviewer Agent 在定性规则审核时调用，用于验证消防设施/安全出口等符号是否存在 |

### 4. cad-visual-enhancement（OCR）

| 项目 | 说明 |
|------|------|
| 路径 | `x3-openfang/skills/cad-visual-enhancement/src/ocr_agent.py` |
| 功能 | 使用阿里云百炼 Qwen-VL-Plus 从 CAD 图纸图像/PDF 中提取文字 |
| 工具名 | `cad_extract_text_from_image` / `cad_extract_text_from_pdf_page` |
| 核心库 | `openai`（兼容百炼 API）, `PyMuPDF`, `Pillow` |
| 提取模式 | `all` / `dimensions` / `annotations` / `title_block` |
| 输入 | 图像文件 + extraction_type + language |
| 输出 | `{ocr_text, token_usage, processing_time_ms}` |
| **复用方式** | CAD_review 的 Reviewer Agent 在 DXF 解析文字不全时（低置信度构件 > 30%），可调用此 OCR 增强。也可用于标题栏信息提取（项目名、图号、设计者） |

### 5. cad-form-table-locator

| 项目 | 说明 |
|------|------|
| 路径 | `x3-openfang/skills/cad-form-table-locator/src/main.py` |
| 功能 | 定位审核报告模板（docx/xlsx/pdf）中的表格栏位并批量填写审核详情 |
| 工具名 | `cad_locate_and_fill` |
| 核心库 | `python-docx`, `openpyxl`, `PyMuPDF`, `rapidfuzz`, `dashscope`(Qwen-VL) |
| 定位策略 | 优先 Qwen-VL-Plus 视觉定位 → 失败降级 rapidfuzz 字符串模糊匹配 |
| 输入 | `template_file_path`, `template_items[]`, `report_details[]`, `output_dir` |
| 输出 | `{output_file_path, locator_report: {matched, fallback, missing}, stats}` |
| **复用方式** | CAD_review 的 SummarizerAgent 生成 ReviewReport 后，可调用此 skill 自动填写标准审核报告模板（Word/Excel），实现"审核结果 → 正式报告文件"的自动化 |

---

## 二、可复用 Agent 设计模式（Prompt + 流程编排）

x3-openfang 的 4 个 CAD agent 定义了完整的审核流水线，其 system prompt 和工作流设计可直接移植到 CAD_review 的 Spring AI Agent 中。

### 1. cad-parser-agent — 解析流水线

| 项目 | 说明 |
|------|------|
| 定义 | `x3-openfang/agents/cad-parser-agent/agent.toml` |
| 职责 | DWG→DXF → DXF解析 → 构件识别 → OCR增强（条件触发）→ 符号检测（条件触发）→ 结果回写 |
| **可复用设计** | |
| - 条件触发 OCR | 低置信度构件占比 > 30% 时才调用 OCR，避免无谓开销 |
| - 条件触发 YOLO | DXF 解析率低（图层混乱）时才调用符号检测 |
| - 质量检查 | 解析成功率 = 成功识别构件数 / 模板要求构件数，低于 70% 视为异常 |
| - 指数退避重试 | 5s/15s/45s，最多 3 次 |
| - 幂等键 | `cad-parser-agent-<scenario>-<yyyymmdd>-<seq>` |
| **对应 CAD_review** | 增强 `CadParserService`，加入条件 OCR/YOLO 增强逻辑 |

### 2. cad-task-dispatcher-agent — 任务分发

| 项目 | 说明 |
|------|------|
| 定义 | `x3-openfang/agents/cad-task-dispatcher-agent/agent.toml` |
| 职责 | 判断任务类型(single/multi/full) → 模板匹配 → 执行方式分配(auto/manual) → 创建任务 |
| **可复用设计** | |
| - 任务类型判断 | 按图纸专业分布：1专业=single, 2-3=multi, 4+=full |
| - 执行方式分配 | 含"结构"专业 → manual，纯建筑 → auto |
| - CONFIRM 两步协议 | 写操作先 confirm=false 获取 hint，再 confirm=true 执行 |
| - 幂等键 | `dispatcher-<scenario>-<yyyymmdd>-<seq>` |
| **对应 CAD_review** | `DispatcherAgent` 可借鉴任务类型判断逻辑和专业分流策略 |

### 3. cad-reviewer-agent — 审核执行

| 项目 | 说明 |
|------|------|
| 定义 | `x3-openfang/agents/cad-reviewer-agent/agent.toml` |
| 职责 | 加载模板审核项 → 加载规则 → 加载解析结果 → 三类规则审核 → 提交 findings |
| **可复用设计** | |
| - 三类规则引擎 | **定量**（数值 vs threshold）、**定性**（LLM 推理）、**计数**（构件数量 vs 阈值） |
| - 模板审核项绑定 | 每条 finding 必须携带 `templateItemId` + `itemNo` |
| - 低置信度处理 | confidence < 0.6 → 标 warning + 转人工 |
| - 规则缺失处理 | 无适用规则 → conclusion=skip + reason |
| - 批量提交 | 单批最多 100 条 findings |
| **对应 CAD_review** | `ReviewerAgent` 当前只有 LLM 定性审核，可增加定量/计数两种确定性审核路径 |

### 4. cad-report-generator-agent — 报告生成

| 项目 | 说明 |
|------|------|
| 定义 | `x3-openfang/agents/cad-report-generator-agent/agent.toml` |
| 职责 | 汇总 findings → 客户端计算合规率/质量评分 → 生成报告 → 逐项填写详情 → 导出 PDF/HTML |
| **可复用设计** | |
| - 合规率公式 | `compliance_rate = pass_count / total_count × 100%` |
| - 质量评分 | `overall_score = compliance_rate × 0.7 + (100 - forced_penalty) × 0.3` |
| - 最终结论 | forced_violations > 0 → reject; recommended > 5 → conditional_pass; 其余 → pass |
| - 逐项填写 | 遍历全部模板审核项，无匹配 finding 的写 N_A |
| - 填写率校验 | `fillRatio < 0.95` 记录 WARNING |
| **对应 CAD_review** | `SummarizerAgent` 可直接采用此评分公式；报告导出可集成 `cad-form-table-locator` skill |

---

## 三、可复用的通用机制

| 机制 | 来源 | 说明 | CAD_review 复用建议 |
|------|------|------|---------------------|
| CONFIRM 两步协议 | 所有 CAD agent | 写操作先 dry-run 再 confirm，防止误操作 | 对接 LuckyCat MCP 时必须实现 |
| 幂等键规范 | 所有 CAD agent | `<agent>-<scenario>-<yyyymmdd>-<seq>` | 审核结果提交到后端时使用 |
| 指数退避重试 | 所有 CAD agent | 5s/15s/45s，最多 3 次 | 替代当前 `max-attempts: 1` 配置 |
| SHA256 文件缓存 | dwg-converter / dxf-parser | 相同文件不重复处理 | `CadParserService` 可加缓存层 |
| 条件触发增强 | cad-parser-agent | 低置信度 > 30% 才 OCR，解析率低才 YOLO | 避免每次都调用昂贵的视觉模型 |
| 构件识别规则引擎 | cad-dxf-parser | 图层名关键词匹配（中英文） | `IrViewService` 可增加构件分类 |
| 三类规则审核 | cad-reviewer-agent | 定量/定性/计数 | 当前只有定性，可扩展 |
| 合规率 + 质量评分 | cad-report-generator | 确定性公式计算 | `SummarizerAgent` 可直接采用 |
| 模板审核项绑定 | cad-reviewer + report-gen | finding 必须关联 templateItemId | 增强 `Finding.java` 字段 |
| VLM + fuzzy 双降级 | cad-form-table-locator | 视觉定位失败 → 字符串模糊匹配 | 报告填写时使用 |

---

## 四、推荐集成架构

```
┌─────────────────────────────────────────────────────────────────┐
│                    CAD_review (Spring Boot)                       │
├─────────────────────────────────────────────────────────────────┤
│  Controller → AgentOrchestrator                                  │
│       │                                                          │
│       ├── DispatcherAgent (LLM: 任务分发 + 专业分流)             │
│       ├── ReviewerAgent  (LLM定性 + 确定性定量/计数)             │
│       └── SummarizerAgent (确定性汇总 + 评分公式)                │
│                                                                  │
│  ┌─── 解析增强层 ───────────────────────────────────────────┐   │
│  │  CadParserService                                         │   │
│  │    ├── HttpParserClient (现有 DXF/DWG 解析)               │   │
│  │    ├── DwgConverter (复用 cad-dwg-converter)               │   │
│  │    ├── SymbolDetector (复用 cad-symbol-detector, 条件触发) │   │
│  │    └── OcrEnhancer (复用 cad-visual-enhancement, 条件触发) │   │
│  └───────────────────────────────────────────────────────────┘   │
│                                                                  │
│  ┌─── 报告生成层 ───────────────────────────────────────────┐   │
│  │  ReportGenerator                                          │   │
│  │    ├── 合规率/质量评分计算 (复用 report-gen 公式)          │   │
│  │    └── FormFiller (复用 cad-form-table-locator)            │   │
│  └───────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
         │              │              │              │
    ┌────┴────┐   ┌────┴────┐   ┌────┴────┐   ┌────┴────┐
    │DWG Conv │   │DXF Parse│   │YOLO Det │   │Qwen OCR │
    │(Python) │   │(Python) │   │(Python) │   │(Python) │
    └─────────┘   └─────────┘   └─────────┘   └─────────┘
    Docker sidecar / subprocess / HTTP microservice
```

---

## 五、优先级建议

| 优先级 | 复用项 | 理由 |
|--------|--------|------|
| P0 | 三类规则审核（定量/定性/计数） | 当前只有 LLM 定性，确定性规则更快更准 |
| P0 | 合规率 + 质量评分公式 | SummarizerAgent 缺少量化评分 |
| P1 | cad-dxf-parser 构件识别 | 增强 IR 语义信息，让 Dispatcher 分派更精准 |
| P1 | 指数退避重试 + 幂等键 | 提升系统健壮性 |
| P2 | cad-visual-enhancement OCR | 条件触发，解决 DXF 文字提取不全的问题 |
| P2 | cad-symbol-detector YOLO | 条件触发，验证消防设施等符号存在性 |
| P3 | cad-form-table-locator | 审核完成后自动填写正式报告模板 |
| P3 | cad-dwg-converter | 当前 HttpParserClient 已支持 DWG，但独立转换可提供更好的版本控制 |

