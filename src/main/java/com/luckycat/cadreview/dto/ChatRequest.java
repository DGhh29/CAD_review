package com.luckycat.cadreview.dto;

import com.luckycat.cadreview.dto.enums.Provider;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ChatRequest {
    private Provider provider;

    @NotBlank(message = "message must not be blank")
    private String message;
}
