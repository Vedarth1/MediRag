package com.medirag.mental_health_service.controller;

import com.medirag.mental_health_service.dto.*;
import com.medirag.mental_health_service.service.MentalHealthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/mental-health")
@RequiredArgsConstructor
public class MentalHealthController {

    private final MentalHealthService mentalHealthService;

    // POST /api/mental-health/chat/session
    @PostMapping("/chat/session")
    public ResponseEntity<ChatSessionResponse> startSession(
            @Valid @RequestBody ChatRequest request,
            @RequestHeader("Authorization") String authHeader) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(mentalHealthService.startSession(request.getMessage(), authHeader));
    }

    // POST /api/mental-health/chat/{sessionId}/message
    @PostMapping("/chat/{sessionId}/message")
    public ResponseEntity<ChatReply> sendMessage(
            @PathVariable Long sessionId,
            @Valid @RequestBody ChatRequest request,
            @RequestHeader("Authorization") String authHeader) {
        return ResponseEntity.ok(
                mentalHealthService.sendMessage(sessionId, request.getMessage(), authHeader));
    }

    // GET /api/mental-health/chat/sessions
    @GetMapping("/chat/sessions")
    public ResponseEntity<List<ChatSessionResponse>> getMySessions(
            @RequestHeader("Authorization") String authHeader) {
        return ResponseEntity.ok(mentalHealthService.getMySessions(authHeader));
    }

    // GET /api/mental-health/chat/{sessionId}/messages
    @GetMapping("/chat/{sessionId}/messages")
    public ResponseEntity<List<ChatMessageResponse>> getMessages(
            @PathVariable Long sessionId,
            @RequestHeader("Authorization") String authHeader) {
        return ResponseEntity.ok(
                mentalHealthService.getSessionMessages(sessionId, authHeader));
    }

    // GET /api/mental-health/resources?category=anxiety
    @GetMapping("/resources")
    public ResponseEntity<List<ResourceResponse>> getResources(
            @RequestParam(required = false) String category) {
        return ResponseEntity.ok(mentalHealthService.getResources(category));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "Mental health service is running"));
    }
}