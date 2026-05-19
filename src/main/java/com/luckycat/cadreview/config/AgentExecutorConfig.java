package com.luckycat.cadreview.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@RequiredArgsConstructor
public class AgentExecutorConfig {

    private final AgentProperties agentProperties;

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
}
