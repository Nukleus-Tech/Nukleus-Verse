package com.nukleus.vrmeeting.controller;

import com.nukleus.vrmeeting.model.EmailOtp;
import com.nukleus.vrmeeting.repository.EmailOtpRepository;
import com.nukleus.vrmeeting.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Random;

@RestController
@RequestMapping("/api/otp")
public class OtpController {

    @Autowired
    private EmailOtpRepository emailOtpRepository;

    @Autowired
    private UserRepository userRepository;

    private boolean isValidEmail(String email) {
        return email != null &&
                email.matches("(?i)^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$");
    }

    @PostMapping("/send")
    public Map<String, Object> sendOtp(@RequestBody Map<String, String> body) {

        String email = body.get("email");

        if (email == null || email.trim().isEmpty()) {
            return Map.of("success", false, "message", "Email is required");
        }

        email = email.trim().toLowerCase();

        if (!isValidEmail(email)) {
            return Map.of("success", false, "message", "Invalid email format");
        }

        if (userRepository.findByEmailIgnoreCase(email) != null) {
            return Map.of("success", false, "message", "Email already exists");
        }

        String otp = String.valueOf(100000 + new Random().nextInt(900000));

        emailOtpRepository.deleteByEmail(email);

        EmailOtp emailOtp = new EmailOtp();
        emailOtp.setEmail(email);
        emailOtp.setOtp(otp);
        emailOtp.setExpiryTime(LocalDateTime.now().plusMinutes(5));

        emailOtpRepository.save(emailOtp);

        System.out.println("OTP for " + email + " = " + otp);

        return Map.of(
                "success", true,
                "message", "OTP generated successfully",
                "email", email
        );
    }

    @PostMapping("/verify")
    public Map<String, Object> verifyOtp(@RequestBody Map<String, String> body) {

        String email = body.get("email");
        String otp = body.get("otp");

        if (email == null || email.trim().isEmpty()) {
            return Map.of("success", false, "message", "Email is required");
        }

        if (otp == null || otp.trim().isEmpty()) {
            return Map.of("success", false, "message", "OTP is required");
        }

        email = email.trim().toLowerCase();
        otp = otp.trim();

        EmailOtp savedOtp = emailOtpRepository.findTopByEmailOrderByIdDesc(email);

        if (savedOtp == null) {
            return Map.of("success", false, "message", "OTP not found");
        }

        if (LocalDateTime.now().isAfter(savedOtp.getExpiryTime())) {
            emailOtpRepository.deleteByEmail(email);
            return Map.of("success", false, "message", "OTP expired");
        }

        if (!savedOtp.getOtp().equals(otp)) {
            return Map.of("success", false, "message", "Invalid OTP");
        }

        emailOtpRepository.deleteByEmail(email);

        return Map.of(
                "success", true,
                "message", "OTP verified successfully",
                "email", email
        );
    }
}