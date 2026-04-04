package com.medirag.health.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medirag.health.dto.*;
import com.medirag.health.entity.*;
import com.medirag.health.repository.*;
import com.medirag.health.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class HealthService {

    private final HealthProfileRepository profileRepository;
    private final SleepLogRepository sleepLogRepository;
    private final MealPlanRepository mealPlanRepository;
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redisTemplate;
    private final OpenAIService openAIService;
    private final ObjectMapper objectMapper;

    @Value("${cache.ttl-minutes:30}")
    private long cacheTtlMinutes;

    // ── Health Profile ──────────────────────────────────────────────────────

    @Transactional
    public HealthProfileResponse saveProfile(HealthProfileRequest request, String authHeader) {
        Long userId = extractUserId(authHeader);

        // Upsert — create if not exists, update if exists
        HealthProfile profile = profileRepository.findByUserId(userId)
                .orElse(HealthProfile.builder().userId(userId).build());

        profile.setHeightCm(request.getHeightCm());
        profile.setWeightKg(request.getWeightKg());
        profile.setBloodType(request.getBloodType());
        profile.setAllergies(request.getAllergies());
        profile.setConditions(request.getConditions());
        // BMI is auto-computed by @PrePersist / @PreUpdate

        HealthProfile saved = profileRepository.save(profile);

        // Invalidate cached meal plans — new profile may need new plan
        redisTemplate.delete("mealplans:" + userId);

        return toProfileResponse(saved);
    }

    public HealthProfileResponse getProfile(String authHeader) {
        Long userId = extractUserId(authHeader);
        HealthProfile profile = profileRepository.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("Health profile not found. Please create one first."));
        return toProfileResponse(profile);
    }

    // ── Sleep Logs ──────────────────────────────────────────────────────────

    @Transactional
    public SleepLogResponse logSleep(SleepLogRequest request, String authHeader) {
        Long userId = extractUserId(authHeader);

        SleepLog log = SleepLog.builder()
                .userId(userId)
                .date(request.getDate())
                .bedTime(request.getBedTime())
                .wakeTime(request.getWakeTime())
                .quality(request.getQuality() != null
                        ? SleepLog.Quality.valueOf(request.getQuality().toUpperCase())
                        : SleepLog.Quality.FAIR)
                .build();
        // durationHours auto-computed by @PrePersist

        return toSleepResponse(sleepLogRepository.save(log));
    }

    public List<SleepLogResponse> getSleepLogs(String authHeader, LocalDate from, LocalDate to) {
        Long userId = extractUserId(authHeader);

        List<SleepLog> logs = (from != null && to != null)
                ? sleepLogRepository.findByUserIdAndDateBetweenOrderByDateAsc(userId, from, to)
                : sleepLogRepository.findByUserIdOrderByDateDesc(userId);

        return logs.stream().map(this::toSleepResponse).collect(Collectors.toList());
    }

    // ── Meal Plans ──────────────────────────────────────────────────────────

    public List<MealPlanResponse> getMealPlans(String authHeader) {
        Long userId = extractUserId(authHeader);
        String cacheKey = "mealplans:" + userId;

        // Cache-Aside — check Redis first
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached,
                        objectMapper.getTypeFactory()
                                .constructCollectionType(List.class, MealPlanResponse.class));
            } catch (Exception ignored) {}
        }

        List<MealPlanResponse> plans = mealPlanRepository
                .findByUserIdOrderByDateDesc(userId)
                .stream()
                .map(this::toMealPlanResponse)
                .collect(Collectors.toList());

        try {
            redisTemplate.opsForValue().set(cacheKey,
                    objectMapper.writeValueAsString(plans),
                    cacheTtlMinutes, TimeUnit.MINUTES);
        } catch (Exception ignored) {}

        return plans;
    }

    @Transactional
    public MealPlanResponse generateMealPlan(String authHeader) {
        Long userId = extractUserId(authHeader);

        // Needs a health profile to personalise the plan
        HealthProfile profile = profileRepository.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException(
                        "Please create your health profile first before generating a meal plan."));

        // Call OpenAI
        AiMealResult aiResponse = openAIService.generateMealPlan(profile);

        // Parse the AI JSON response
        MealPlan plan = parseMealPlanFromAI(aiResponse.getMealsJson(), userId,aiResponse.isAiGenerated());
        MealPlan saved = mealPlanRepository.save(plan);

        // Invalidate the meal plan cache
        redisTemplate.delete("mealplans:" + userId);

        return toMealPlanResponse(saved);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private Long extractUserId(String authHeader) {
        String token = authHeader.replace("Bearer ", "");
        return jwtUtil.extractUserId(token);
    }

    private MealPlan parseMealPlanFromAI(String aiJson, Long userId, Boolean isAiGenerated) {
        try {
            Map<String, Object> parsed = objectMapper.readValue(aiJson, Map.class);
            return MealPlan.builder()
                    .userId(userId)
                    .date(LocalDate.now())
                    .totalCalories(toInt(parsed.get("totalCalories")))
                    .proteinG(toDouble(parsed.get("proteinG")))
                    .carbsG(toDouble(parsed.get("carbsG")))
                    .fatG(toDouble(parsed.get("fatG")))
                    .mealsJson(objectMapper.writeValueAsString(parsed.get("meals")))
                    .aiGenerated(isAiGenerated)
                    .build();
        } catch (Exception e) {
            log.error("Failed to parse AI meal plan response: {}", e.getMessage());
            // Save raw response so we don't lose the AI output
            return MealPlan.builder()
                    .userId(userId)
                    .date(LocalDate.now())
                    .totalCalories(2000)
                    .mealsJson(aiJson)
                    .aiGenerated(true)
                    .build();
        }
    }

    private Integer toInt(Object val) {
        if (val instanceof Integer) return (Integer) val;
        if (val instanceof Number) return ((Number) val).intValue();
        return null;
    }

    private Double toDouble(Object val) {
        if (val instanceof Double) return (Double) val;
        if (val instanceof Number) return ((Number) val).doubleValue();
        return null;
    }

    private String getBmiCategory(Double bmi) {
        if (bmi == null) return null;
        if (bmi < 18.5) return "Underweight";
        if (bmi < 25.0) return "Normal";
        if (bmi < 30.0) return "Overweight";
        return "Obese";
    }

    // ── Mappers ─────────────────────────────────────────────────────────────

    private HealthProfileResponse toProfileResponse(HealthProfile p) {
        return HealthProfileResponse.builder()
                .id(p.getId())
                .userId(p.getUserId())
                .heightCm(p.getHeightCm())
                .weightKg(p.getWeightKg())
                .bloodType(p.getBloodType())
                .allergies(p.getAllergies())
                .conditions(p.getConditions())
                .bmi(p.getBmi())
                .bmiCategory(getBmiCategory(p.getBmi()))
                .build();
    }

    private SleepLogResponse toSleepResponse(SleepLog s) {
        return SleepLogResponse.builder()
                .id(s.getId())
                .date(s.getDate())
                .bedTime(s.getBedTime())
                .wakeTime(s.getWakeTime())
                .durationHours(s.getDurationHours())
                .quality(s.getQuality() != null ? s.getQuality().name() : null)
                .build();
    }

    private MealPlanResponse toMealPlanResponse(MealPlan m) {
        return MealPlanResponse.builder()
                .id(m.getId())
                .date(m.getDate())
                .totalCalories(m.getTotalCalories())
                .proteinG(m.getProteinG())
                .carbsG(m.getCarbsG())
                .fatG(m.getFatG())
                .mealsJson(m.getMealsJson())
                .aiGenerated(m.getAiGenerated())
                .build();
    }
}