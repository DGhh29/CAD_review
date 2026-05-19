# CAD_review 风格约定

- Java 17，Spring Boot 4，优先构造器注入；需要指定 ChatClient 时使用 `@Qualifier`。
- DTO 主要使用 Lombok：`@Data`、`@Builder`、`@NoArgsConstructor`、`@AllArgsConstructor`。
- 控制器统一返回 `ApiResult<T>`。
- 业务文案、prompt、错误说明优先中文。
- LLM 结构化输出复用 Spring AI `BeanOutputConverter`，输出包装类优先使用普通 Lombok DTO，避免直接解析裸 `List<T>`。
- CAD 解析结果以 Jackson `JsonNode` 传递，给 LLM 前应先做摘要或裁剪，避免全量实体直接进入上下文。