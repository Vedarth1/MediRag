package com.medirag.mental_health_service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatReply {
    private Long sessionId;
    private ChatMessageResponse userMessage;
    private ChatMessageResponse assistantReply;
    private int totalMessages;
}