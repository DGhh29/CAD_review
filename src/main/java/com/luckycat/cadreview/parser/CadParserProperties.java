package com.luckycat.cadreview.parser;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * CAD 解析子系统的配置项，对应 application.yml 中
 * {@code cad-review.parser.*} 段的内容。
 *
 * <p>注入到 {@link HttpParserClient}，决定它如何调用外部
 * CAD 解析 HTTP 服务（默认假设是本机 8000 端口的 Python 服务）。
 *
 * <p>典型配置示例：
 * <pre>
 * cad-review:
 *   parser:
 *     http-url: http://localhost:8000
 *     timeout-seconds: 120
 *     max-entities: 0
 *     max-texts: 0
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "cad-review.parser")
public class CadParserProperties {

    /** 外部 CAD 解析 HTTP 服务的根地址（不带 {@code /parse} 路径）。 */
    private String httpUrl = "http://localhost:8000";

    /**
     * 单次解析的最长等待时间（秒）。
     * 仅作用在 read timeout 上；connect timeout 在 {@link HttpParserClient} 内部固定为 5 秒。
     */
    private int timeoutSeconds = 120;

    /**
     * 调用解析服务时透传的 {@code max_entities} 上限。
     * 小于等于 0 表示不限制，由 Python 端返回全量图元明细。
     */
    private int maxEntities = 10000;

    /**
     * 调用解析服务时透传的 {@code max_texts} 上限，
     * 限制单张图纸文字标注的最大数量；小于等于 0 表示不限制。
     */
    private int maxTexts = 2000;
}
