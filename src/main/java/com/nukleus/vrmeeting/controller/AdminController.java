package com.nukleus.vrmeeting.controller;

import com.nukleus.vrmeeting.model.Admin;
import com.nukleus.vrmeeting.repository.AdminRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import com.nukleus.vrmeeting.repository.UserRepository;
import com.nukleus.vrmeeting.repository.MeetingRepository;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    @Autowired
    private AdminRepository adminRepository;
    @Autowired
   private UserRepository userRepository;

@Autowired
    private MeetingRepository meetingRepository;

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Admin request) {

        if (request.getEmail() == null || request.getEmail().trim().isEmpty()) {
            return Map.of("success", false, "message", "Email is required");
        }

        if (request.getPassword() == null || request.getPassword().trim().isEmpty()) {
            return Map.of("success", false, "message", "Password is required");
        }

        String email = request.getEmail().trim().toLowerCase();
        String password = request.getPassword().trim();

        Admin admin = adminRepository.findByEmailIgnoreCase(email);

        if (admin == null || !admin.getPassword().equals(password)) {
            return Map.of("success", false, "message", "Invalid credentials");
        }

        return Map.of(
                "success", true,
                "message", "Login Successful",
                "adminId", admin.getId(),
                "email", admin.getEmail()
        );
    }
    @GetMapping("/users")
public Map<String, Object> getAllUsers() {
    return Map.of(
            "success", true,
            "users", userRepository.findAll()
    );
}

@GetMapping("/meetings")
public Map<String, Object> getAllMeetings() {
    return Map.of(
            "success", true,
            "meetings", meetingRepository.findAll()
    );
}
}