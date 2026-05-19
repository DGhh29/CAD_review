package com.luckycat.cadreview.config;

import com.luckycat.cadreview.dto.enums.Provider;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "cad-review.llm")
public class LlmProperties {
    private Provider defaultProvider = Provider.OPENAI;
    private int timeoutMs = 30000;
    private Retry retry = new Retry();

    @Data
    public static class Retry {
        private int maxAttempts = 1;
        private int backoffMs = 1000;
    }
}
