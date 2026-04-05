package com.medirag.mental_health_service.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import com.medirag.mental_health_service.entity.ChatMessage;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    List<ChatMessage> findBySessionIdOrderBySentAtAsc(Long sessionId);
}