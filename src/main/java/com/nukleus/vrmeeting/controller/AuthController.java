package com.nukleus.vrmeeting.controller;

import com.nukleus.vrmeeting.model.User;
import com.nukleus.vrmeeting.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")

public class AuthController {

    @Autowired
    private UserRepository userRepository;

    private boolean isValidEmail(String email) {
        return email != null &&
                email.matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$");
    }

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody User user) {

        if (user.getName() == null || user.getName().trim().isEmpty()) {
            return Map.of("success", false, "message", "Name is required");
        }

        if (user.getEmail() == null || user.getEmail().trim().isEmpty()) {
            return Map.of("success", false, "message", "Email is required");
        }

        if (!isValidEmail(user.getEmail())) {
            return Map.of("success", false, "message", "Invalid email format");
        }

        if (user.getPassword() == null || user.getPassword().trim().isEmpty()) {
            return Map.of("success", false, "message", "Password is required");
        }

        if (userRepository.findByEmail(user.getEmail()) != null) {
            return Map.of("success", false, "message", "Email already exists");
        }

        userRepository.save(user);

        return Map.of(
                "success", true,
                "message", "Registration Successful",
                "userId", user.getId(),
                "name", user.getName(),
                "email", user.getEmail()
        );
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody User loginUser) {

        if (loginUser.getEmail() == null || loginUser.getEmail().trim().isEmpty()) {
            return Map.of("success", false, "message", "Email is required");
        }

        if (!isValidEmail(loginUser.getEmail())) {
            return Map.of("success", false, "message", "Invalid email format");
        }

        if (loginUser.getPassword() == null || loginUser.getPassword().trim().isEmpty()) {
            return Map.of("success", false, "message", "Password is required");
        }

        User dbUser = userRepository.findByEmail(loginUser.getEmail());

        if (dbUser == null) {
            return Map.of("success", false, "message", "User not found");
        }

        if (!dbUser.getPassword().equals(loginUser.getPassword())) {
            return Map.of("success", false, "message", "Wrong password");
        }

        return Map.of(
                "success", true,
                "message", "Login Successful",
                "userId", dbUser.getId(),
                "name", dbUser.getName(),
                "email", dbUser.getEmail()
        );
    }
}