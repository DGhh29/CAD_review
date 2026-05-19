package com.luckycat.cadreview.service;

import com.luckycat.cadreview.dto.ChatResponse;
import com.luckycat.cadreview.dto.enums.Provider;

public interface ChatService {
    ChatResponse chat(Provider provider, String message);
}
