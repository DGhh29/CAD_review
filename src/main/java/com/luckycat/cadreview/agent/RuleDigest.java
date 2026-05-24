package com.luckycat.cadreview.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 给 Dispatcher 使用的法规/规则摘要。
 *
 * <p>它比 ReviewRule 更短，只保留任务分派必需字段，避免把完整规则提示反复塞进分派模型。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleDigest {
    private String ruleId;
    private String clauseId;
    private String title;
    private String scope;
    private String version;
    private String outputRequirement;
    private String priorityHint;
}
