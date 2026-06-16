package com.nukleus.vrmeeting.controller;

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

        User user = userRepository.findByEmail(avatarData.getEmail().trim().toLowerCase());

        if (user == null) {
            response.put("success", false);
            response.put("message", "User not found");
            return response;
        }

        user.setImageUrl(avatarData.getImageUrl());
        user.setAvatarUrl(avatarData.getAvatarUrl());
        user.setRiggedGlbUrl(avatarData.getRiggedGlbUrl());
        user.setWalkingGlbUrl(avatarData.getWalkingGlbUrl());
        user.setRunningGlbUrl(avatarData.getRunningGlbUrl());
        user.setAvatarStatus(avatarData.getAvatarStatus());
        user.setMeshyTaskId(avatarData.getMeshyTaskId());

        userRepository.save(user);

        response.put("success", true);
        response.put("message", "Avatar data saved successfully");
        response.put("email", user.getEmail());
        response.put("avatarStatus", user.getAvatarStatus());

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

    User user = userRepository.findByEmail(email.trim().toLowerCase());

    if (user == null) {
        response.put("success", false);
        response.put("message", "User not found");
        return response;
    }

    response.put("success", true);
    response.put("email", user.getEmail());
    response.put("imageUrl", user.getImageUrl());
    response.put("avatarUrl", user.getAvatarUrl());
    response.put("riggedGlbUrl", user.getRiggedGlbUrl());
    response.put("walkingGlbUrl", user.getWalkingGlbUrl());
    response.put("runningGlbUrl", user.getRunningGlbUrl());
    response.put("avatarStatus", user.getAvatarStatus());
    response.put("meshyTaskId", user.getMeshyTaskId());

    return response;
}
}