package com.luckycat.cadreview.dto;

import com.luckycat.cadreview.agent.AgentRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentMessage {

    private String messageId;
    private AgentRole fromRole;
    private AgentRole toRole;
    private String taskId;
    private String content;

    @Builder.Default
    private Map<String, Object> attributes = new LinkedHashMap<>();
}
