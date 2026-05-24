package com.luckycat.cadreview;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * CAD 图纸自动审核系统 Spring Boot 启动类。
 *
 * <p>整个应用是一个多 Agent 协同的审图服务：HTTP 入口（controller 包）
 * 接收图纸文件后，{@link com.luckycat.cadreview.agent.AgentOrchestrator}
 * 负责把请求拆成"解析 → Dispatcher 分派 → Reviewer 并行审核 → Summarizer 汇总"四个阶段。
 *
 * <p>该类只负责装配 Spring 上下文，无业务逻辑；
 * 真正的配置散落在 config 包（{@code AgentProperties}、{@code LlmProperties}、
 * {@code CadParserProperties} 等）以及 application.yml 中。
 */
@SpringBootApplication
public class CadReviewApplication {
    public static void main(String[] args) {
        SpringApplication.run(CadReviewApplication.class, args);
    }
}
