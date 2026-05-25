package com.luckycat.cadreview.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Reviewer 阶段并行任务的线程池装配。
 *
 * <p>本配置产出 Reviewer、补证抽取、异步 run 三类线程池。
 * {@link com.luckycat.cadreview.agent.AgentOrchestrator} 通过
 * {@code @Qualifier("reviewerTaskExecutor")} 注入它，把每个 ReviewTask
 * 包成 {@link java.util.concurrent.CompletableFuture} 并行投递给 Reviewer Agent。
 *
 * <p>线程池参数全部来自 {@link AgentProperties.Reviewer}（对应 application.yml 的
 * {@code cad-review.agent.reviewer.*}）：调大并行度需要在那里改，而不是改这里的代码。
 *
 * <p>注意：Dispatcher 阶段也会复用本线程池（Orchestrator.dispatchWithTimeout 里的 supplyAsync），
 * 但 Dispatcher 同一时刻只会有一个任务，不会与 Reviewer 形成显著竞争。
 */
@Configuration
@RequiredArgsConstructor
public class AgentExecutorConfig {

    private final AgentProperties agentProperties;

    /**
     * 创建专供 Reviewer 并行执行的线程池。
     *
     * <p>参数来源：
     * <ul>
     *   <li>corePoolSize ← reviewer.corePoolSize（常驻线程）</li>
     *   <li>maxPoolSize  ← reviewer.parallelMax（最大并行度，也是真正能同时跑的 Reviewer 数）</li>
     *   <li>queueCapacity ← reviewer.queueCapacity（排队上限，超出走默认 AbortPolicy）</li>
     * </ul>
     * 线程名前缀 {@code cad-reviewer-} 便于在日志/线程 dump 里一眼识别评审线程。
     */
    @Bean(name = "reviewerTaskExecutor")
    public Executor reviewerTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(agentProperties.getReviewer().getCorePoolSize());
        executor.setMaxPoolSize(agentProperties.getReviewer().getParallelMax());
        executor.setQueueCapacity(agentProperties.getReviewer().getQueueCapacity());
        executor.setThreadNamePrefix("cad-reviewer-");
        executor.initialize();
        return executor;
    }

    @Bean(name = "evidenceRepairTaskExecutor")
    public Executor evidenceRepairTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(agentProperties.getEvidenceRepair().getCorePoolSize());
        executor.setMaxPoolSize(agentProperties.getEvidenceRepair().getParallelMax());
        executor.setQueueCapacity(agentProperties.getEvidenceRepair().getQueueCapacity());
        executor.setThreadNamePrefix("cad-evidence-repair-");
        executor.initialize();
        return executor;
    }

    @Bean(name = "reviewRunTaskExecutor")
    public Executor reviewRunTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(agentProperties.getRunner().getCorePoolSize());
        executor.setMaxPoolSize(agentProperties.getRunner().getParallelMax());
        executor.setQueueCapacity(agentProperties.getRunner().getQueueCapacity());
        executor.setThreadNamePrefix("cad-review-run-");
        executor.initialize();
        return executor;
    }
}
