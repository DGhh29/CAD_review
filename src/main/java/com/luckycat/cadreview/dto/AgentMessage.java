package com.luckycat.cadreview.dto;

import com.luckycat.cadreview.agent.AgentRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 之间通信用的通用消息体。
 *
 * <p>承载 Dispatcher / Reviewer / Summarizer 三方在协作过程中互相传递的语义事件
 * （如 Dispatcher 把任务派发给 Reviewer、Reviewer 把审核完成事件回报给 Summarizer 等），
 * 用于审计跟踪、链路日志、未来扩展异步消息总线时作为统一载体。
 *
 * <p>注意：当前主流程是直接函数调用（见 {@code AgentOrchestrator}），并未走基于该 DTO 的消息总线，
 * 因此本类目前主要作为预留契约，便于后续切到队列 / SSE 推送时使用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentMessage {

    // 消息唯一 ID，建议生成 UUID；用于在日志和监控里串联同一条消息的发送、消费、回执
    private String messageId;

    // 发送方角色，取值见 AgentRole（DISPATCHER / REVIEWER / SUMMARIZER）
    private AgentRole fromRole;

    // 接收方角色；广播场景可约定特殊值或留空，由消费方过滤
    private AgentRole toRole;

    // 关联的 ReviewTask.taskId，把消息绑回某个具体审核任务，便于追踪上下文
    private String taskId;

    // 消息正文，通常是给目标 Agent 的指令文本或回执说明；结构化数据建议放到 attributes
    private String content;

    // 扩展属性键值对，比如携带 Finding 列表、token 用量、错误信息等结构化负载
    // 用 LinkedHashMap 保留插入顺序，方便日志按写入顺序回放
    @Builder.Default
    private Map<String, Object> attributes = new LinkedHashMap<>();
}
