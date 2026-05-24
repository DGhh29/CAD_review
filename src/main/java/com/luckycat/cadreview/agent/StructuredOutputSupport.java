package com.luckycat.cadreview.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luckycat.cadreview.dto.ChatResponse;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

import java.util.function.Function;

/**
 * 统一封装 LLM "结构化输出"调用的底层支持组件。
 *
 * <p>Dispatcher / Reviewer / Summarizer 三个 Agent 都通过它发起 LLM 请求，核心机制：
 * <ol>
 *   <li><b>系统提示拼装</b>：在原始 systemPrompt 之后追加 {@link BeanOutputConverter#getFormat()} 生成的
 *       JSON Schema 描述，告诉 LLM 必须按目标 POJO 的字段结构输出严格 JSON。</li>
 *   <li><b>用户提示</b>：业务侧序列化好的输入（IR/规则/任务等）直接当作 user 消息。</li>
 *   <li><b>原始文本归一化</b>：通过 {@link #normalizeJson} 容忍 LLM 偶尔输出的 ```json``` 代码围栏、
 *       前后赘述文字，定位真正的 JSON 主体后再交给 BeanOutputConverter 反序列化。</li>
 *   <li><b>业务校验</b>：可选 {@code validator}（由各 Agent 自己实现）在反序列化后做语义校验和补默认值；
 *       校验失败抛异常，触发下一次重试。</li>
 *   <li><b>有限次重试</b>：循环上限为 {@code maxAttempts + 1}（即首试 + maxAttempts 次重试），
 *       任何阶段失败都把异常吞下并记录 warn 日志，全部失败后抛 {@link StructuredOutputException}
 *       并把最后一次的原始文本带上，便于排查 LLM 是不是返回了奇怪的内容。</li>
 * </ol>
 *
 * <p>顺带把 Spring AI 的 token usage 适配到内部 {@link ChatResponse.TokenUsage}，方便上层统一计量。
 */
@Slf4j
@Component
public class StructuredOutputSupport {

    /**
     * 构造器保留 ObjectMapper 参数以便后续扩展；当前实现不依赖它，
     * 因为 BeanOutputConverter 内部自带 Jackson 配置。
     */
    public StructuredOutputSupport(ObjectMapper objectMapper) {
    }

    /**
     * 调用 LLM 并把回包反序列化为 {@code outputType} 类型的 POJO。
     *
     * @param client       具体使用的 ChatClient（OpenAI 或 Anthropic）；由调用方按 Provider 选好。
     * @param systemPrompt 业务级系统提示，会被自动追加 JSON 输出格式约束。
     * @param userPrompt   业务级用户提示（一般是序列化后的输入 JSON）。
     * @param outputType   期望的输出 POJO 类型，BeanOutputConverter 会据此推断 schema 并反序列化。
     * @param maxAttempts  额外重试次数；总尝试次数 = maxAttempts + 1。一般来自
     *                     {@code AgentProperties.<Role>.getMaxAttempts()}。
     * @param operation    用于日志的可读操作名（如 "dispatcher" / "reviewer-TASK-001"）。
     * @param validator    业务级校验/规范化函数；返回值会替换原 output 写回结果，抛异常即触发重试。
     * @param <T>          输出 POJO 类型。
     * @return 包含解析后 POJO + 原始文本 + 模型名 + token 用量的封装结果。
     * @throws StructuredOutputException 所有尝试都失败时抛出，附最后一次原始文本以便排障。
     */
    public <T> StructuredResult<T> call(
            ChatClient client,
            String systemPrompt,
            String userPrompt,
            Class<T> outputType,
            int maxAttempts,
            String operation,
            Function<T, T> validator) {
        // BeanOutputConverter 同时承担两件事：1) 生成给 LLM 看的 schema 描述；2) 把 JSON 文本反序列化为 POJO。
        BeanOutputConverter<T> converter = new BeanOutputConverter<>(outputType);
        // 把 schema 拼到原系统提示后面 —— 让 LLM 在已有"角色/规则"基础上再多遵循一条"必须按此 JSON 结构输出"。
        String systemWithFormat = systemPrompt + "\n\n" + converter.getFormat();

        Exception lastError = null;
        String rawContent = null;
        // 注意循环上界为 <= maxAttempts，意味着首次尝试不计入"重试"；总调用次数 = maxAttempts + 1。
        for (int attempt = 0; attempt <= maxAttempts; attempt++) {
            try {
                var aiResponse = client.prompt()
                        .system(systemWithFormat)
                        .user(userPrompt)
                        .call()
                        .chatResponse();

                rawContent = aiResponse.getResult().getOutput().getText();
                // 先归一化（去 markdown 围栏 / 截断到第一段 JSON），再交给 converter 反序列化。
                T output = converter.convert(normalizeJson(rawContent));
                if (validator != null) {
                    // 业务校验失败会抛异常，被外层 catch 抓到后进入下一轮重试。
                    output = validator.apply(output);
                }
                ChatResponse.TokenUsage usage = null;
                if (aiResponse.getMetadata() != null && aiResponse.getMetadata().getUsage() != null) {
                    var springUsage = aiResponse.getMetadata().getUsage();
                    usage = ChatResponse.TokenUsage.builder()
                            .promptTokens(springUsage.getPromptTokens() != null ? springUsage.getPromptTokens().longValue() : null)
                            .completionTokens(springUsage.getCompletionTokens() != null ? springUsage.getCompletionTokens().longValue() : null)
                            .build();
                }
                return new StructuredResult<>(
                        output,
                        rawContent,
                        aiResponse.getMetadata() != null ? aiResponse.getMetadata().getModel() : null,
                        usage);
            } catch (Exception ex) {
                // 不区分异常类型一律重试 —— 网络抖动 / 解析失败 / 校验失败都可能在下一次成功
                lastError = ex;
                log.warn("{} attempt {} failed: {}", operation, attempt + 1, ex.getMessage());
            }
        }
        throw new StructuredOutputException(operation, rawContent, lastError);
    }

    /**
     * 把 LLM 返回的原始字符串清洗为可被 Jackson 解析的 JSON。
     *
     * <p>常见场景：
     * <ul>
     *   <li>模型用 {@code ```json ... ```} 围栏包裹了答案 —— 去掉首尾代码围栏。</li>
     *   <li>模型在 JSON 前后多写了"以下是 JSON"之类的赘述 —— 通过定位首个 {@code {} / [} 与最后一个
     *       {@code }} / {@code ]} 截取主体。</li>
     * </ul>
     */
    private String normalizeJson(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Structured output is empty");
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            // 去掉开头的 ```language 行
            int firstLineBreak = trimmed.indexOf('\n');
            if (firstLineBreak >= 0) {
                trimmed = trimmed.substring(firstLineBreak + 1).trim();
            }
            // 去掉结尾的 ```
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
            }
        }
        // 即便没有围栏，也可能存在 LLM 的多余说明文字 —— 截到第一个 JSON 起点至最后一个 JSON 终点。
        int objectStart = indexOfJsonStart(trimmed);
        int objectEnd = indexOfJsonEnd(trimmed);
        if (objectStart >= 0 && objectEnd > objectStart) {
            trimmed = trimmed.substring(objectStart, objectEnd + 1);
        }
        return trimmed;
    }

    /** 取 '{' 与 '[' 中先出现的那个，作为 JSON 主体起点。 */
    private int indexOfJsonStart(String text) {
        int objectStart = text.indexOf('{');
        int arrayStart = text.indexOf('[');
        if (objectStart < 0) {
            return arrayStart;
        }
        if (arrayStart < 0) {
            return objectStart;
        }
        return Math.min(objectStart, arrayStart);
    }

    /** 取 '}' 与 ']' 中后出现的那个，作为 JSON 主体终点。 */
    private int indexOfJsonEnd(String text) {
        int objectEnd = text.lastIndexOf('}');
        int arrayEnd = text.lastIndexOf(']');
        return Math.max(objectEnd, arrayEnd);
    }

    /**
     * 单次结构化调用的完整结果：解析后 POJO + 原始文本 + 实际调用的模型名 + token 用量。
     * 原始文本主要给排查链路用（比如 verifyVerdict 不一致时回看 LLM 究竟说了啥）。
     */
    @Data
    @AllArgsConstructor
    public static class StructuredResult<T> {
        private T output;
        private String rawContent;
        private String model;
        private ChatResponse.TokenUsage tokenUsage;
    }

    /**
     * 当所有重试都失败时抛出。携带 operation 名称与最后一次原始文本，
     * 让上层日志/监控能够知道是哪个 Agent 的哪次调用挂了，以及 LLM 究竟返回了什么。
     */
    public static class StructuredOutputException extends RuntimeException {
        private final String operation;
        private final String rawContent;

        public StructuredOutputException(String operation, String rawContent, Throwable cause) {
            super(operation + " structured output parsing failed", cause);
            this.operation = operation;
            this.rawContent = rawContent;
        }

        public String getOperation() {
            return operation;
        }

        public String getRawContent() {
            return rawContent;
        }
    }
}
