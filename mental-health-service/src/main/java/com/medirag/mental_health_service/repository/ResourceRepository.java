package com.medirag.mental_health_service.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import com.medirag.mental_health_service.entity.Resource;

public interface ResourceRepository extends JpaRepository<Resource, Long> {
    List<Resource> findByCategory(String category);
    List<Resource> findByType(Resource.ResourceType type);
}