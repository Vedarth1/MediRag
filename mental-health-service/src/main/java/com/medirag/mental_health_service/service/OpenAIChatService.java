package com.medirag.mental_health_service.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.medirag.mental_health_service.entity.ChatMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class OpenAIChatService {

    @Value("${openai.api-key}")
    private String apiKey;

    @Value("${openai.api-url}")
    private String apiUrl;

    @Value("${openai.model}")
    private String model;

    private final RestTemplate restTemplate = new RestTemplate();

    // The system prompt — sets the AI's persona for every conversation
    private static final String SYSTEM_PROMPT = """
        You are a compassionate and professional mental health support assistant for MediRAG.
        Your role is to provide emotional support, coping strategies, and wellness guidance.
        
        Important boundaries:
        - You are NOT a replacement for professional therapy or medical advice
        - If someone expresses thoughts of self-harm or suicide, always encourage them to
          contact emergency services (112 in India) or a licensed therapist immediately
        - Keep responses warm, empathetic, and concise (2-4 paragraphs maximum)
        - Always end with a gentle follow-up question to keep the conversation going
        """;

    /**
     * Send a message along with the full conversation history.
     * This gives the AI context of everything said before.
     */
    public String chat(List<ChatMessage> history, String newUserMessage) {

        // Build the messages array: system + full history + new message
        List<Map<String, String>> messages = new ArrayList<>();

        // 1. System prompt — always first
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));

        // 2. Full conversation history
        for (ChatMessage msg : history) {
            messages.add(Map.of("role", msg.getRole(), "content", msg.getContent()));
        }

        // 3. New user message
        messages.add(Map.of("role", "user", "content", newUserMessage));

        Map<String, Object> requestBody = Map.of(
            "model", model,
            "messages", messages,
            "max_tokens", 500,
            "temperature", 0.8    // slightly higher for more natural, empathetic responses
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                apiUrl,
                new HttpEntity<>(requestBody, headers),
                Map.class
            );

            List<Map<String, Object>> choices =
                (List<Map<String, Object>>) response.getBody().get("choices");
            Map<String, Object> message =
                (Map<String, Object>) choices.get(0).get("message");

            return (String) message.get("content");

        } catch (Exception e) {
            log.error("OpenAI chat API failed: {}", e.getMessage());
            return getFallbackResponse();
        }
    }

    private String getFallbackResponse() {
        return "I'm here to support you, and I want you to know your feelings are valid. " +
               "I'm experiencing a brief technical issue right now, but please don't hesitate " +
               "to share what's on your mind. If you're in immediate distress, please reach out " +
               "to a trusted person or contact emergency services. How are you feeling right now?";
    }
}