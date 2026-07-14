package com.nukleus.vrmeeting.controller;

import com.nukleus.vrmeeting.model.User;
import com.nukleus.vrmeeting.repository.UserRepository;
import com.nukleus.vrmeeting.security.JwtUtil;
import com.nukleus.vrmeeting.dto.GoogleLoginRequest;
import com.nukleus.vrmeeting.service.GoogleAuthService;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;

import java.util.HashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
        @Autowired
        private JwtUtil jwtUtil;

        @Autowired
        private UserRepository userRepository;
        @Autowired
        private GoogleAuthService googleAuthService;

        private boolean isValidEmail(String email) {
                return email != null &&
                                email.matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
        }

        @PostMapping("/register")
        public Map<String, Object> register(@RequestBody User user) {

                if (user.getName() == null || user.getName().trim().isEmpty()) {
                        return Map.of(
                                        "success", false,
                                        "message", "Name is required");
                }

                if (user.getEmail() == null || user.getEmail().trim().isEmpty()) {
                        return Map.of(
                                        "success", false,
                                        "message", "Email is required");
                }

                String email = user.getEmail().trim().toLowerCase();

                user.setEmail(email);
                user.setName(user.getName().trim());

                if (!isValidEmail(email)) {
                        return Map.of(
                                        "success", false,
                                        "message", "Invalid email format");
                }

                if (user.getPassword() == null || user.getPassword().trim().isEmpty()) {
                        return Map.of(
                                        "success", false,
                                        "message", "Password is required");
                }

                user.setPassword(user.getPassword().trim());

                if (userRepository.findByEmailIgnoreCase(email) != null) {
                        return Map.of(
                                        "success", false,
                                        "message", "Email already exists");
                }

                // Admin Users Module fields

                user.setAccountStatus("ACTIVE");
                user.setCreatedAt(LocalDateTime.now());

                userRepository.save(user);

                return Map.of(
                                "success", true,
                                "message", "Registration Successful",
                                "userId", user.getId(),
                                "name", user.getName(),
                                "email", user.getEmail());
        }

        @PostMapping("/login")
        public Map<String, Object> login(@RequestBody User loginUser) {

                if (loginUser.getEmail() == null ||
                                loginUser.getEmail().trim().isEmpty()) {

                        return Map.of(
                                        "success", false,
                                        "message", "Email is required");
                }

                String email = loginUser.getEmail()
                                .trim()
                                .toLowerCase();

                if (!isValidEmail(email)) {

                        return Map.of(
                                        "success", false,
                                        "message", "Invalid email format");
                }

                if (loginUser.getPassword() == null ||
                                loginUser.getPassword().trim().isEmpty()) {

                        return Map.of(
                                        "success", false,
                                        "message", "Password is required");
                }

                String password = loginUser.getPassword().trim();

                User dbUser = userRepository.findByEmailIgnoreCase(email);

                if (dbUser == null) {

                        return Map.of(
                                        "success", false,
                                        "message", "User not found");
                }
                if ("BLOCKED".equalsIgnoreCase(dbUser.getAccountStatus())) {

                        return Map.of(
                                        "success", false,
                                        "message", "Account is blocked");
                }

                if (!dbUser.getPassword().equals(password)) {

                        return Map.of(
                                        "success", false,
                                        "message", "Wrong password");
                }

                // Update last login
                dbUser.setLastLogin(LocalDateTime.now());

                userRepository.save(dbUser);

                String token = jwtUtil.generateToken(dbUser.getEmail());

                Map<String, Object> userData = new java.util.HashMap<>();
                userData.put("id", dbUser.getId());
                userData.put("name", dbUser.getName());
                userData.put("email", dbUser.getEmail());

                return Map.of(
                                "success", true,
                                "message", "Login Successful",
                                "token", token,
                                "user", userData);
        }

        @PostMapping("/google")
        public Map<String, Object> googleLogin(
                        @RequestBody GoogleLoginRequest request) {

                try {

                        GoogleIdToken.Payload payload = googleAuthService.verifyToken(
                                        request.getIdToken());

                        String googleId = payload.getSubject();

                        String email = payload.getEmail();

                        String name = (String) payload.get("name");

                        String image = (String) payload.get("picture");

                        User user = userRepository.findByGoogleId(googleId);

                        // Existing google user nahi mila
                        if (user == null) {

                                user = userRepository.findByEmailIgnoreCase(email);

                                if (user == null) {

                                        user = new User();

                                        user.setEmail(email);
                                        user.setName(name);

                                        user.setAccountStatus("ACTIVE");
                                        user.setCreatedAt(
                                                        LocalDateTime.now());

                                }

                                user.setGoogleId(googleId);
                                user.setImageUrl(image);

                                userRepository.save(user);

                        }

                        user.setLastLogin(
                                        LocalDateTime.now());

                        userRepository.save(user);

                        String token = jwtUtil.generateToken(
                                        user.getEmail());

                        Map<String, Object> userData = new HashMap<>();

                        userData.put(
                                        "id",
                                        user.getId());

                        userData.put(
                                        "name",
                                        user.getName());

                        userData.put(
                                        "email",
                                        user.getEmail());

                        return Map.of(
                                        "success",
                                        true,

                                        "message",
                                        "Google Login Successful",

                                        "token",
                                        token,

                                        "user",
                                        userData);

                } catch (Exception e) {

                        return Map.of(
                                        "success",
                                        false,

                                        "message",
                                        "Invalid Google Token");

                }

        }
}