package com.medirag.appointment.controller;

import com.medirag.appointment.dto.*;
import com.medirag.appointment.service.AppointmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/appointments")
@RequiredArgsConstructor
public class AppointmentController {
    
    private final AppointmentService appointmentService;

    // GET /api/appointments/doctors?specialization=cardiology
    @GetMapping("/doctors")
    public ResponseEntity<List<DoctorResponse>> getDoctors(
            @RequestParam(required = false) String specialization) {
        return ResponseEntity.ok(appointmentService.getDoctors(specialization));
    }

    // GET /api/appointments/slots/{doctorId}
    @GetMapping("/slots/{doctorId}")
    public ResponseEntity<List<TimeSlotResponse>> getSlots(@PathVariable Long doctorId) {
        return ResponseEntity.ok(appointmentService.getAvailableSlots(doctorId));
    }

    // POST /api/appointments/book
    @PostMapping("/book")
    public ResponseEntity<AppointmentResponse> book(
            @Valid @RequestBody BookingRequest request,
            @RequestHeader("Authorization") String authHeader) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(appointmentService.bookAppointment(request, authHeader));
    }

    // GET /api/appointments/my
    @GetMapping("/my")
    public ResponseEntity<List<AppointmentResponse>> myAppointments(
            @RequestHeader("Authorization") String authHeader) {
        return ResponseEntity.ok(appointmentService.getMyAppointments(authHeader));
    }

    // PUT /api/appointments/{id}/cancel
    @PutMapping("/{id}/cancel")
    public ResponseEntity<AppointmentResponse> cancel(
            @PathVariable Long id,
            @RequestHeader("Authorization") String authHeader) {
        return ResponseEntity.ok(appointmentService.cancelAppointment(id, authHeader));
    }

    // POST /api/appointments/slots
    @PostMapping("/slots")
    public ResponseEntity<SlotResponse> createSlot(
            @RequestBody CreateSlotRequest request,
            @RequestHeader("Authorization") String authHeader) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(appointmentService.createSlot(request, authHeader));
    }

    // DELETE /api/appointments/slots/{slotId}
    @DeleteMapping("/slots/{slotId}")
    public ResponseEntity<Map<String,String>> deleteSlot(
            @PathVariable Long slotId,
            @RequestHeader("Authorization") String authHeader) {
        appointmentService.deleteSlot(slotId, authHeader);
        return ResponseEntity.ok(Map.of(
            "message", "Slot deleted successfully",
            "status", "success"
        ));
    }

    // PUT /api/appointments/slots/{slotId}
    @PutMapping("/slots/{slotId}")
    public ResponseEntity<SlotResponse> updateSlot(
            @PathVariable Long slotId,
            @RequestBody CreateSlotRequest request,
            @RequestHeader("Authorization") String authHeader) {

        SlotResponse response = appointmentService.updateSlot(slotId, request, authHeader);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "Appointment service is running"));
    }
}