package com.luckycat.cadreview.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 异步审图运行服务配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "cad-review.review-run")
public class ReviewRunProperties {
    /** 是否启动时自动创建异步审图运行表。 */
    private boolean initializeSchema = true;
}
