package com.medirag.appointment.service;

import com.medirag.appointment.dto.*;
import com.medirag.appointment.entity.*;
import com.medirag.appointment.repository.*;
import com.medirag.appointment.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AppointmentService {

    private final DoctorRepository doctorRepository;
    private final TimeSlotRepository timeSlotRepository;
    private final AppointmentRepository appointmentRepository;
    private final StringRedisTemplate redisTemplate;
    private final EmailService emailService;
    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;

    @Value("${cache.ttl-minutes:10}")
    private long cacheTtlMinutes;

    // ── Get doctors with Redis Cache-Aside pattern ──────────────────────────

    public List<DoctorResponse> getDoctors(String specialization) {
        String cacheKey = "doctors:" + (specialization != null ? specialization.toLowerCase() : "all");

        // 1. Check Redis first
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, DoctorResponse.class));
            } catch (Exception ignored) {}
        }

        // 2. Cache miss — query PostgreSQL
        List<Doctor> doctors = (specialization != null)
                ? doctorRepository.findBySpecializationIgnoreCase(specialization)
                : doctorRepository.findByAvailableTrue();

        List<DoctorResponse> response = doctors.stream()
                .map(this::toDoctorResponse)
                .collect(Collectors.toList());

        // 3. Store in Redis with TTL
        try {
            redisTemplate.opsForValue().set(cacheKey,
                    objectMapper.writeValueAsString(response),
                    cacheTtlMinutes, TimeUnit.MINUTES);
        } catch (Exception ignored) {}

        return response;
    }

    // ── Get available slots for a doctor ───────────────────────────────────

    public List<TimeSlotResponse> getAvailableSlots(Long doctorId) {
        return timeSlotRepository.findByDoctorIdAndIsBookedFalse(doctorId)
                .stream()
                    .map(slot -> TimeSlotResponse.builder()
                            .id(slot.getId())
                            .doctorId(slot.getDoctor().getId())
                            .doctorName(slot.getDoctor().getName())
                            .slotTime(slot.getSlotTime())
                            .isBooked(slot.getIsBooked())
                            .build())
                    .collect(Collectors.toList());
    }

    // ── Book an appointment ─────────────────────────────────────────────────

    @Transactional   // if anything fails, the whole operation rolls back
    public AppointmentResponse bookAppointment(BookingRequest request, String authHeader) {

        // 1. Extract patient info from JWT
        String token = authHeader.replace("Bearer ", "");
        Long patientId = jwtUtil.extractUserId(token);
        String patientEmail = jwtUtil.extractEmail(token);

        // 2. Find and validate the slot
        TimeSlot slot = timeSlotRepository.findById(request.getSlotId())
                .orElseThrow(() -> new RuntimeException("Slot not found"));

        if (slot.getIsBooked()) {
            throw new RuntimeException("This slot is already booked");
        }

        if (!slot.getDoctor().getId().equals(request.getDoctorId())) {
            throw new RuntimeException("Slot does not belong to the specified doctor");
        }

        // 3. Mark the slot as booked
        slot.setIsBooked(true);
        timeSlotRepository.save(slot);

        // 4. Create the appointment record
        Appointment appointment = Appointment.builder()
                .patientId(patientId)
                .patientEmail(patientEmail)
                .doctor(slot.getDoctor())
                .slot(slot)
                .notes(request.getNotes())
                .build();

        Appointment saved = appointmentRepository.save(appointment);

        // 5. Invalidate the doctor cache — availability has changed
        invalidateDoctorCache(slot.getDoctor().getSpecialization());

        // 6. Send confirmation email asynchronously (non-blocking)
        emailService.sendBookingConfirmation(saved);

        return toAppointmentResponse(saved);
    }

    // ── Get patient's appointments ──────────────────────────────────────────

    public List<AppointmentResponse> getMyAppointments(String authHeader) {
        String token = authHeader.replace("Bearer ", "");
        Long patientId = jwtUtil.extractUserId(token);
        return appointmentRepository.findByPatientId(patientId)
                .stream()
                .map(this::toAppointmentResponse)
                .collect(Collectors.toList());
    }

    // ── Cancel appointment ──────────────────────────────────────────────────

    @Transactional
    public AppointmentResponse cancelAppointment(Long appointmentId, String authHeader) {
        String token = authHeader.replace("Bearer ", "");
        Long patientId = jwtUtil.extractUserId(token);

        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new RuntimeException("Appointment not found"));

        // Patients can only cancel their own appointments
        if (!appointment.getPatientId().equals(patientId)) {
            throw new RuntimeException("Not authorised to cancel this appointment");
        }

        // Free up the slot again
        appointment.getSlot().setIsBooked(false);
        timeSlotRepository.save(appointment.getSlot());

        appointment.setStatus(Appointment.Status.CANCELLED);
        Appointment saved = appointmentRepository.save(appointment);

        // Invalidate cache and send email
        invalidateDoctorCache(appointment.getDoctor().getSpecialization());
        emailService.sendCancellationNotice(saved);

        return toAppointmentResponse(saved);
    }

    // ── Create Appointment Slot by doctor ──────────────────────────────────────────────────

    @Transactional
    public SlotResponse createSlot(CreateSlotRequest request, String authHeader) {

        String token = authHeader.replace("Bearer ", "");

        String role = jwtUtil.extractRole(token);
        Long doctorId = jwtUtil.extractUserId(token);

        if (!"DOCTOR".equals(role)) {
            throw new RuntimeException("Only doctors can create slots");
        }

        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new RuntimeException("Doctor not found"));

        TimeSlot slot = TimeSlot.builder()
                .doctor(doctor)
                .slotTime(request.getSlotTime())
                .isBooked(false)
                .build();

        TimeSlot saved = timeSlotRepository.save(slot);

        return SlotResponse.builder()
                .id(saved.getId())
                .slotTime(saved.getSlotTime())
                .isBooked(saved.getIsBooked())
                .build();
    }
    
    // ── Delete Appointment Slot by doctor ──────────────────────────────────────────────────

    @Transactional
    public void deleteSlot(Long slotId, String authHeader) {

        String token = authHeader.replace("Bearer ", "");

        String role = jwtUtil.extractRole(token);
        Long doctorId = jwtUtil.extractUserId(token);

        if (!"DOCTOR".equals(role)) {
            throw new RuntimeException("Only doctors can delete slots");
        }

        TimeSlot slot = timeSlotRepository.findById(slotId)
                .orElseThrow(() -> new RuntimeException("Slot not found"));

        if (!slot.getDoctor().getId().equals(doctorId)) {
            throw new RuntimeException("You can delete only your slots");
        }

        if (slot.getIsBooked()) {
            throw new RuntimeException("Cannot delete a booked slot");
        }

        timeSlotRepository.delete(slot);
    }

    // ── Update Appointment Slot by doctor ──────────────────────────────────────────────────

    @Transactional
    public SlotResponse updateSlot(Long slotId, CreateSlotRequest request, String authHeader) {

        String token = authHeader.replace("Bearer ", "");

        String role = jwtUtil.extractRole(token);
        Long doctorId = jwtUtil.extractUserId(token);

        // 🔐 Only doctor allowed
        if (!"DOCTOR".equals(role)) {
            throw new RuntimeException("Only doctors can update slots");
        }

        // 📌 Fetch slot
        TimeSlot slot = timeSlotRepository.findById(slotId)
                .orElseThrow(() -> new RuntimeException("Slot not found"));

        // 🔐 Ensure doctor owns this slot
        if (!slot.getDoctor().getId().equals(doctorId)) {
            throw new RuntimeException("You can update only your slots");
        }

        // ❌ Prevent updating booked slot
        if (slot.getIsBooked()) {
            throw new RuntimeException("Cannot update a booked slot");
        }

        // 🔄 Update slot time
        slot.setSlotTime(request.getSlotTime());

        TimeSlot updated = timeSlotRepository.save(slot);

        return SlotResponse.builder()
                .id(updated.getId())
                .slotTime(updated.getSlotTime())
                .isBooked(updated.getIsBooked())
                .build();
    }

    // ── Cache invalidation ──────────────────────────────────────────────────

    private void invalidateDoctorCache(String specialization) {
        redisTemplate.delete("doctors:all");
        redisTemplate.delete("doctors:" + specialization.toLowerCase());
    }

    // ── Mappers ─────────────────────────────────────────────────────────────

    private DoctorResponse toDoctorResponse(Doctor d) {
        return DoctorResponse.builder()
                .id(d.getId())
                .name(d.getName())
                .specialization(d.getSpecialization())
                .email(d.getEmail())
                .phone(d.getPhone())
                .available(d.getAvailable())
                .build();
    }

    private AppointmentResponse toAppointmentResponse(Appointment a) {
        return AppointmentResponse.builder()
                .id(a.getId())
                .patientId(a.getPatientId())
                .patientEmail(a.getPatientEmail())
                .doctorName(a.getDoctor().getName())
                .specialization(a.getDoctor().getSpecialization())
                .slotTime(a.getSlot().getSlotTime())
                .status(a.getStatus().name())
                .bookedAt(a.getBookedAt())
                .build();
    }
}