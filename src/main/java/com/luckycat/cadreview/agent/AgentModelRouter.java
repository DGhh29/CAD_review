package com.luckycat.cadreview.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 按 Agent 角色选择轻量/深度模型。
 *
 * <p>任务规划、分派和前置清洗默认走轻量模型；正式审核、复核和汇总默认走深度模型。
 * 这样业务代码只表达"当前是什么角色"，不直接关心底层供应商和具体模型。
 */
@Component
public class AgentModelRouter {

    private final ChatClient lightweightReviewClient;
    private final ChatClient deepReviewClient;

    public AgentModelRouter(
            @Qualifier("lightweightReviewClient") ChatClient lightweightReviewClient,
            @Qualifier("deepReviewClient") ChatClient deepReviewClient) {
        this.lightweightReviewClient = lightweightReviewClient;
        this.deepReviewClient = deepReviewClient;
    }

    public ChatClient clientFor(AgentRole role) {
        if (role == AgentRole.REGULATION_PLANNER
                || role == AgentRole.DISPATCHER
                || role == AgentRole.PRE_CLEANER) {
            return lightweightReviewClient;
        }
        return deepReviewClient;
    }
}
