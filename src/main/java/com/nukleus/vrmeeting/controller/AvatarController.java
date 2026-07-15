package com.nukleus.vrmeeting.controller;

import java.time.LocalDateTime;

import com.nukleus.vrmeeting.model.User;
import com.nukleus.vrmeeting.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/avatar")
public class AvatarController {

    @Autowired
    private UserRepository userRepository;

    @PostMapping("/save")
    public Map<String, Object> saveAvatar(@RequestBody User avatarData) {

        Map<String, Object> response = new HashMap<>();

        if (avatarData.getEmail() == null || avatarData.getEmail().trim().isEmpty()) {
            response.put("success", false);
            response.put("message", "Email is required");
            return response;
        }

        String email = avatarData.getEmail().trim().toLowerCase();

        User user = userRepository.findByEmailIgnoreCase(email);

        if (user == null) {
            user = new User();
            user.setEmail(email);
            

            user.setAccountStatus("ACTIVE");
            user.setCreatedAt(LocalDateTime.now());
        }

        if (avatarData.getName() != null && !avatarData.getName().trim().isEmpty()) {
            user.setName(avatarData.getName().trim());
        }

        if (avatarData.getPassword() != null && !avatarData.getPassword().trim().isEmpty()) {
            user.setPassword(avatarData.getPassword().trim());
        }

        user.setImageUrl(avatarData.getImageUrl());
        user.setAvatarUrl(avatarData.getAvatarUrl());
        user.setRiggedGlbUrl(avatarData.getRiggedGlbUrl());
        user.setWalkingGlbUrl(avatarData.getWalkingGlbUrl());
        // user.setRunningGlbUrl(avatarData.getRunningGlbUrl());
        user.setIdleGlbUrl(avatarData.getIdleGlbUrl());
        user.setSittingGlbUrl(avatarData.getSittingGlbUrl());
        user.setAvatarStatus(avatarData.getAvatarStatus());
        user.setMeshyTaskId(avatarData.getMeshyTaskId());

        User savedUser = userRepository.save(user);

        response.put("success", true);
        response.put("message", "Avatar data saved successfully");
        response.put("email", savedUser.getEmail());
        response.put("name", savedUser.getName());
        response.put("avatarStatus", savedUser.getAvatarStatus());

        return response;
    }

    @GetMapping("/by-email")
    public Map<String, Object> getAvatarByEmail(@RequestParam String email) {

        Map<String, Object> response = new HashMap<>();

        if (email == null || email.trim().isEmpty()) {
            response.put("success", false);
            response.put("message", "Email is required");
            return response;
        }

        email = email.trim().toLowerCase();

        User user = userRepository.findByEmailIgnoreCase(email);

        if (user == null) {
            response.put("success", false);
            response.put("message", "User not found for email: " + email);
            return response;
        }

        response.put("success", true);
        response.put("email", user.getEmail());
        response.put("name", user.getName());
        response.put("imageUrl", user.getImageUrl());
        response.put("avatarUrl", user.getAvatarUrl());
        response.put("riggedGlbUrl", user.getRiggedGlbUrl());
        response.put("walkingGlbUrl", user.getWalkingGlbUrl());
        // response.put("runningGlbUrl", user.getRunningGlbUrl());
        response.put("idleGlbUrl", user.getIdleGlbUrl());
        response.put("sittingGlbUrl", user.getSittingGlbUrl());
        response.put("avatarStatus", user.getAvatarStatus());
        response.put("meshyTaskId", user.getMeshyTaskId());

        return response;
    }
}