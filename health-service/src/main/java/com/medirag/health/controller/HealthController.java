package com.medirag.health.controller;

import com.medirag.health.dto.*;
import com.medirag.health.service.HealthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/health")
@RequiredArgsConstructor
public class HealthController {

    private final HealthService healthService;

    // POST /api/health/profile — create or update
    @PostMapping("/profile")
    public ResponseEntity<HealthProfileResponse> saveProfile(
            @RequestBody HealthProfileRequest request,
            @RequestHeader("Authorization") String authHeader) {
        return ResponseEntity.ok(healthService.saveProfile(request, authHeader));
    }

    // GET /api/health/profile
    @GetMapping("/profile")
    public ResponseEntity<HealthProfileResponse> getProfile(
            @RequestHeader("Authorization") String authHeader) {
        return ResponseEntity.ok(healthService.getProfile(authHeader));
    }

    // POST /api/health/sleep
    @PostMapping("/sleep")
    public ResponseEntity<SleepLogResponse> logSleep(
            @Valid @RequestBody SleepLogRequest request,
            @RequestHeader("Authorization") String authHeader) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(healthService.logSleep(request, authHeader));
    }

    // GET /api/health/sleep?from=2026-01-01&to=2026-01-31
    @GetMapping("/sleep")
    public ResponseEntity<List<SleepLogResponse>> getSleepLogs(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(healthService.getSleepLogs(authHeader, from, to));
    }

    // POST /api/health/meal-plan/generate
    @PostMapping("/meal-plan/generate")
    public ResponseEntity<MealPlanResponse> generateMealPlan(
            @RequestHeader("Authorization") String authHeader) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(healthService.generateMealPlan(authHeader));
    }

    // GET /api/health/meal-plan
    @GetMapping("/meal-plan")
    public ResponseEntity<List<MealPlanResponse>> getMealPlans(
            @RequestHeader("Authorization") String authHeader) {
        return ResponseEntity.ok(healthService.getMealPlans(authHeader));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "Health service is running"));
    }
}