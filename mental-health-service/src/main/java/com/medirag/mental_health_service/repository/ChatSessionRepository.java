package com.medirag.mental_health_service.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.medirag.mental_health_service.entity.ChatSession;
import java.util.List;
import java.util.Optional;

public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {
    List<ChatSession> findByUserIdOrderByLastMessageAtDesc(Long userId);
    Optional<ChatSession> findByIdAndUserId(Long id, Long userId);
}
