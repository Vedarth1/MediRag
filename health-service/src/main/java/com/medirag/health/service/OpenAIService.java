package com.medirag.health.service;

import com.medirag.health.dto.AiMealResult;
import com.medirag.health.entity.HealthProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class OpenAIService {

    @Value("${openai.api-key}")
    private String apiKey;

    @Value("${openai.api-url}")
    private String apiUrl;

    @Value("${openai.model}")
    private String model;

    // RestTemplate is Spring's HTTP client for calling external APIs
    private final RestTemplate restTemplate = new RestTemplate();

    public AiMealResult generateMealPlan(HealthProfile profile) {
        String prompt = buildPrompt(profile);

        // Build the request body OpenAI expects
        Map<String, Object> requestBody = Map.of(
            "model", model,
            "messages", List.of(
                Map.of("role", "system", "content",
                    "You are a certified nutritionist. Always respond with valid JSON only. " +
                    "No markdown, no explanation, just the JSON object."),
                Map.of("role", "user", "content", prompt)
            ),
            "max_tokens", 1000,
            "temperature", 0.7
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(apiUrl, entity, Map.class);

            // Navigate the OpenAI response structure
            List<Map<String, Object>> choices =
                (List<Map<String, Object>>) response.getBody().get("choices");
            Map<String, Object> message =
                (Map<String, Object>) choices.get(0).get("message");
            String content = (String) message.get("content");


            return AiMealResult.builder()
                    .mealsJson(content)
                    .aiGenerated(true)
                    .build();

        } catch (Exception e) {
            log.error("OpenAI API call failed: {}", e.getMessage());

            return AiMealResult.builder()
                    .mealsJson(getDefaultMealPlan(profile))
                    .aiGenerated(false)
                    .build();
        }
    }

    private String buildPrompt(HealthProfile profile) {
        return String.format("""
            Generate a one-day meal plan for a person with these stats:
            - BMI: %.1f (%s)
            - Weight: %.1f kg, Height: %.1f cm
            - Blood type: %s
            - Allergies: %s
            - Medical conditions: %s
            
            Respond with ONLY this JSON structure:
            {
              "totalCalories": 2000,
              "proteinG": 120,
              "carbsG": 250,
              "fatG": 65,
              "meals": {
                "breakfast": {"name": "...", "calories": 500, "items": ["..."]},
                "lunch":     {"name": "...", "calories": 700, "items": ["..."]},
                "dinner":    {"name": "...", "calories": 600, "items": ["..."]},
                "snacks":    {"name": "...", "calories": 200, "items": ["..."]}
              }
            }
            """,
            profile.getBmi() != null ? profile.getBmi() : 22.0,
            getBmiCategory(profile.getBmi()),
            profile.getWeightKg() != null ? profile.getWeightKg() : 70.0,
            profile.getHeightCm() != null ? profile.getHeightCm() : 170.0,
            profile.getBloodType() != null ? profile.getBloodType() : "Unknown",
            profile.getAllergies() != null ? profile.getAllergies() : "None",
            profile.getConditions() != null ? profile.getConditions() : "None"
        );
    }

    private String getBmiCategory(Double bmi) {
        if (bmi == null) return "Normal";
        if (bmi < 18.5) return "Underweight";
        if (bmi < 25.0) return "Normal";
        if (bmi < 30.0) return "Overweight";
        return "Obese";
    }

    // Fallback if OpenAI is unavailable
    private String getDefaultMealPlan(HealthProfile profile) {
        return """
            {
              "totalCalories": 2000,
              "proteinG": 100,
              "carbsG": 250,
              "fatG": 65,
              "meals": {
                "breakfast": {"name": "Oats with fruit", "calories": 400, "items": ["oats","banana","milk"]},
                "lunch":     {"name": "Grilled chicken salad", "calories": 600, "items": ["chicken","lettuce","tomato"]},
                "dinner":    {"name": "Dal and rice", "calories": 700, "items": ["dal","rice","vegetables"]},
                "snacks":    {"name": "Mixed nuts", "calories": 300, "items": ["almonds","walnuts","cashews"]}
              }
            }
            """;
    }
}