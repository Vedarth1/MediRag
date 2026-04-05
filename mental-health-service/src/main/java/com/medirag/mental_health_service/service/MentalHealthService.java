package com.medirag.mental_health_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medirag.mental_health_service.dto.*;
import com.medirag.mental_health_service.entity.*;
import com.medirag.mental_health_service.repository.*;
import com.medirag.mental_health_service.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class MentalHealthService {

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final ResourceRepository resourceRepository;
    private final OpenAIChatService openAIChatService;
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${cache.resources-ttl-minutes:60}")
    private long resourcesTtlMinutes;

    // ── Chat Session ────────────────────────────────────────────────────────

    @Transactional
    public ChatSessionResponse startSession(String firstMessage, String authHeader) {
        Long userId = extractUserId(authHeader);

        // Title = first 50 chars of the opening message
        String title = firstMessage.length() > 50
                ? firstMessage.substring(0, 50) + "..."
                : firstMessage;

        ChatSession session = ChatSession.builder()
                .userId(userId)
                .title(title)
                .build();

        ChatSession saved = sessionRepository.save(session);
        return toSessionResponse(saved);
    }

    // ── Send Message and Get AI Reply ───────────────────────────────────────

    @Transactional
    public ChatReply sendMessage(Long sessionId, String userMessage, String authHeader) {
        Long userId = extractUserId(authHeader);

        // Verify session belongs to this user
        ChatSession session = sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new RuntimeException("Chat session not found"));

        if (!session.getIsActive()) {
            throw new RuntimeException("This chat session has been closed");
        }

        // Load full conversation history for context
        List<ChatMessage> history = messageRepository
                .findBySessionIdOrderBySentAtAsc(sessionId);

        // Call OpenAI with history + new message
        String aiReply = openAIChatService.chat(history, userMessage);

        // Save user's message
        ChatMessage userMsg = ChatMessage.builder()
                .session(session)
                .role("user")
                .content(userMessage)
                .build();
        ChatMessage savedUser = messageRepository.save(userMsg);

        // Save AI's reply
        ChatMessage assistantMsg = ChatMessage.builder()
                .session(session)
                .role("assistant")
                .content(aiReply)
                .build();
        ChatMessage savedAssistant = messageRepository.save(assistantMsg);

        // Update session timestamp
        session.setLastMessageAt(LocalDateTime.now());
        sessionRepository.save(session);

        return ChatReply.builder()
                .sessionId(sessionId)
                .userMessage(toMessageResponse(savedUser))
                .assistantReply(toMessageResponse(savedAssistant))
                .totalMessages(history.size() + 2)  // history + user + assistant
                .build();
    }

    // ── Get All Sessions ────────────────────────────────────────────────────

    public List<ChatSessionResponse> getMySessions(String authHeader) {
        Long userId = extractUserId(authHeader);
        return sessionRepository.findByUserIdOrderByLastMessageAtDesc(userId)
                .stream()
                .map(this::toSessionResponse)
                .collect(Collectors.toList());
    }

    // ── Get Messages in a Session ───────────────────────────────────────────

    public List<ChatMessageResponse> getSessionMessages(Long sessionId, String authHeader) {
        Long userId = extractUserId(authHeader);

        // Ownership check
        sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new RuntimeException("Chat session not found"));

        return messageRepository.findBySessionIdOrderBySentAtAsc(sessionId)
                .stream()
                .map(this::toMessageResponse)
                .collect(Collectors.toList());
    }

    // ── Relaxation Resources with Redis Cache ───────────────────────────────

    public List<ResourceResponse> getResources(String category) {
        String cacheKey = "resources:" + (category != null ? category.toLowerCase() : "all");

        // Cache-Aside — resources rarely change so TTL is 60 minutes
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached,
                        objectMapper.getTypeFactory()
                                .constructCollectionType(List.class, ResourceResponse.class));
            } catch (Exception ignored) {}
        }

        List<Resource> resources = (category != null)
                ? resourceRepository.findByCategory(category.toLowerCase())
                : resourceRepository.findAll();

        List<ResourceResponse> response = resources.stream()
                .map(this::toResourceResponse)
                .collect(Collectors.toList());

        try {
            redisTemplate.opsForValue().set(
                    cacheKey,
                    objectMapper.writeValueAsString(response),
                    resourcesTtlMinutes, TimeUnit.MINUTES
            );
        } catch (Exception ignored) {}

        return response;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private Long extractUserId(String authHeader) {
        return jwtUtil.extractUserId(authHeader.replace("Bearer ", ""));
    }

    private ChatSessionResponse toSessionResponse(ChatSession s) {
        return ChatSessionResponse.builder()
                .id(s.getId())
                .title(s.getTitle())
                .isActive(s.getIsActive())
                .createdAt(s.getCreatedAt())
                .lastMessageAt(s.getLastMessageAt())
                .messageCount(s.getMessages().size())
                .build();
    }

    private ChatMessageResponse toMessageResponse(ChatMessage m) {
        return ChatMessageResponse.builder()
                .id(m.getId())
                .role(m.getRole())
                .content(m.getContent())
                .sentAt(m.getSentAt())
                .build();
    }

    private ResourceResponse toResourceResponse(Resource r) {
        return ResourceResponse.builder()
                .id(r.getId())
                .title(r.getTitle())
                .type(r.getType().name())
                .content(r.getContent())
                .category(r.getCategory())
                .durationMinutes(r.getDurationMinutes())
                .build();
    }
}
