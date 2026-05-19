# CAD_review 项目概览

- 这是一个 Spring Boot 4 + Spring AI 2.0-M4 的 CAD 图纸审图 Demo 项目。
- 主要能力包括：DXF/DWG 解析、RAG/知识检索、单次审核 mock、以及本次新增的多 Agent 审图链路。
- 关键包：`controller/`、`service/`、`parser/`、`dto/`、`config/`、`agent/`。
- 外部依赖侧重 OpenAI / Anthropic 模型、PostgreSQL pgvector、Python CAD 解析脚本。