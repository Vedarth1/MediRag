package com.medirag.appointment.service;

import com.medirag.appointment.entity.Appointment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    
    private final JavaMailSender mailSender;

    // @Async means this runs in a separate thread — caller doesn't wait
    @Async
    public void sendBookingConfirmation(Appointment appointment) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(appointment.getPatientEmail());
            message.setSubject("Appointment Confirmed — MediRAG");
            message.setText(buildConfirmationText(appointment));
            mailSender.send(message);
            log.info("Confirmation email sent to {}", appointment.getPatientEmail());
        } catch (Exception e) {
            // Never let email failure break the booking
            log.error("Failed to send confirmation email: {}", e.getMessage());
        }
    }

    @Async
    public void sendCancellationNotice(Appointment appointment) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(appointment.getPatientEmail());
            message.setSubject("Appointment Cancelled — MediRAG");
            message.setText("Your appointment with Dr. " + appointment.getDoctor().getName()
                    + " on " + appointment.getSlot().getSlotTime()
                    + " has been cancelled.");
            mailSender.send(message);
        } catch (Exception e) {
            log.error("Failed to send cancellation email: {}", e.getMessage());
        }
    }

    private String buildConfirmationText(Appointment appointment) {
        return String.format("""
                Dear Patient,
                
                Your appointment has been confirmed.
                
                Doctor      : Dr. %s
                Specialization : %s
                Date & Time : %s
                Status      : %s
                
                Please arrive 10 minutes early.
                
                — MediRAG Team
                """,
                appointment.getDoctor().getName(),
                appointment.getDoctor().getSpecialization(),
                appointment.getSlot().getSlotTime(),
                appointment.getStatus()
        );
    }
}