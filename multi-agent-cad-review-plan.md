# 多 Agent CAD AI 审图系统 - 优化后实现计划

## 目标

在保留现有 `ReviewMockService` 单次审核 demo 的基础上，新增一个规则驱动的多 Agent 审图链路：

1. **Dispatcher Agent**：读取 CAD 解析 IR 的摘要视图和规则库，生成可并行执行的 `ReviewTask`。
2. **Reviewer Agent**：按任务裁剪 IR 子集，只审核相关实体和图层，输出带证据链的 `Finding`。
3. **Summarizer Agent**：用确定性逻辑汇总结果，检测冲突、coverage、未锚定证据比例，并在需要时尝试二次验证。

Demo 阶段继续采用同步接口，新增：

- `POST /api/review/submit`
- `POST /api/review/dispatch-only`

## 对原计划的优化

原计划方向正确，但有几处需要收敛，避免 demo 阶段过度复杂：

1. **复用现有 ChatClient**
   - 当前项目已有 `openAiReviewClient` 和 `anthropicReviewClient`。
   - 不再额外创建 agent 专用 ChatClient Bean，避免重复配置和启动复杂度。

2. **IR 裁剪先做确定性实现**
   - Dispatcher 只接收 `summary/statistics/semantic/layers/texts/dimensions/audit_pack` 等摘要。
   - Reviewer 只接收按 `entityIds` 和 `layerNames` 过滤后的实体子集。
   - token 预算在当前阶段用 `maxReviewEntities` 控制实体数量，后续再接入真实 token 估算。

3. **二次验证可失败降级**
   - Summarizer 保留二次验证能力，但验证模型失败不影响主流程返回。
   - 验证失败会写入 `Finding.verification`，整体结果按确定性规则保守判定为 `PENDING_REVIEW`。

4. **规则配置落在 `cad-review.agent.rules`**
   - `id + version` 唯一。
   - `id/version/clauseId/title/scope/promptFragment` 必填。
   - Dispatcher 只能使用已配置规则，不允许自创规则。

5. **保守输出结论**
   - 发现确定 `FAIL` 且没有更高优先级的不确定因素时，整体为 `FAIL`。
   - 有任务失败、任务截断、冲突、未锚定证据比例过高、或无任务可审时，整体为 `PENDING_REVIEW`。
   - 全部任务成功且没有失败/冲突/高未锚定比例时，整体为 `PASS`。

## 当前落地范围

### 新增包结构

```text
src/main/java/com/luckycat/cadreview/
├── agent/
│   ├── AgentRole.java
│   ├── AgentContext.java
│   ├── AgentOrchestrator.java
│   ├── DispatcherAgent.java
│   ├── ReviewerAgent.java
│   ├── SummarizerAgent.java
│   ├── IrViewService.java
│   └── StructuredOutputSupport.java
├── config/
│   ├── AgentProperties.java
│   └── AgentExecutorConfig.java
├── controller/
│   └── ReviewController.java
├── dto/
│   ├── AgentMessage.java
│   ├── ConflictGroup.java
│   ├── ReviewCoverage.java
│   ├── ReviewReport.java
│   ├── ReviewRule.java
│   └── ReviewTask.java
└── prompt/
    └── PromptTemplates.java
```

### 修改文件

- `src/main/java/com/luckycat/cadreview/dto/Verification.java`
  - 兼容扩展 `verifiedVerdict`、`verifiedReason` 字段。
- `src/main/resources/application.yml`
  - 增加 `cad-review.agent` 配置和 demo 规则库。
- `src/main/resources/prompts/*.md`
  - 增加 Dispatcher、Reviewer、Summarizer system prompt。

## 核心执行流程

1. `AgentOrchestrator` 根据文件扩展调用 `CadParserService.parseDxf/parseDwg`。
2. `IrViewService` 生成 Dispatcher 摘要视图。
3. `DispatcherAgent` 结合规则库输出任务清单。
4. `AgentOrchestrator` 按 `maxReviewTasks` 截断任务，记录 `skippedTaskIds/skippedRuleIds`。
5. `ReviewerAgent` 使用 `CompletableFuture` 并行执行任务，单任务失败只进入 failed 列表。
6. `SummarizerAgent` 汇总 findings、失败任务、跳过任务，生成 `ReviewReport`。

## 关键边界

- CAD 图纸中的图层名、文字、标注全部视为不可信数据，只作为 JSON 数据传给模型。
- Reviewer 输出的 `FAIL` 必须有规则、证据文本，以及实体 ID 或 boundingBox。
- Dispatcher 输出的 `ruleIds` 必须属于配置规则库。
- `areaId` 无法确定时统一使用 `UNKNOWN`，不作为 IR 裁剪依据。
- `unanchoredRate > 0.5` 时整体结果进入 `PENDING_REVIEW`。

## 验证计划

优先补确定性单元测试：

1. `IrViewServiceTest`
   - 验证摘要视图不携带全量实体。
   - 验证按 `entityIds/layerNames` 裁剪实体。

2. `SummarizerAgentTest`
   - 验证冲突检测。
   - 验证 coverage、partial、overallVerdict 的保守判定。

3. `AgentOrchestratorTest`
   - 验证任务截断排序和 skipped 元数据。

最小验证命令：

```bash
mvn -Dtest=IrViewServiceTest,SummarizerAgentTest,AgentOrchestratorTest test
```

如需真实模型验证：

```bash
curl -X POST http://localhost:8090/api/review/dispatch-only \
  -F "file=@sample.dxf"

curl -X POST http://localhost:8090/api/review/submit \
  -F "file=@sample.dxf"
```
