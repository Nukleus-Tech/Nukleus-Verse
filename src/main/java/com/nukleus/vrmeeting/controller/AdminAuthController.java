package com.nukleus.vrmeeting.controller;

import com.nukleus.vrmeeting.model.Admin;
import com.nukleus.vrmeeting.repository.AdminRepository;
import com.nukleus.vrmeeting.security.JwtUtil;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/admin")
public class AdminAuthController {


    @Autowired
    private AdminRepository adminRepository;

    @Autowired
    private JwtUtil jwtUtil;


    @PostMapping("/login")
    public Map<String, Object> login(
            @RequestBody Admin request) {


        if (request.getEmail() == null ||
                request.getEmail().trim().isEmpty()) {

            return Map.of(
                    "success",
                    false,
                    "message",
                    "Email is required"
            );
        }


        if (request.getPassword() == null ||
                request.getPassword().trim().isEmpty()) {

            return Map.of(
                    "success",
                    false,
                    "message",
                    "Password is required"
            );
        }


        String email =
                request.getEmail()
                        .trim()
                        .toLowerCase();


        String password =
                request.getPassword()
                        .trim();


        Admin admin =
                adminRepository
                        .findByEmailIgnoreCase(email);


        if (admin == null ||
                !admin.getPassword()
                        .trim()
                        .equals(password)) {


            return Map.of(
                    "success",
                    false,
                    "message",
                    "Invalid credentials"
            );
        }


        Map<String,Object> adminData =
                new HashMap<>();


        adminData.put(
                "id",
                admin.getId()
        );

        adminData.put(
                "name",
                admin.getName()
        );

        adminData.put(
                "email",
                admin.getEmail()
        );

        adminData.put(
                "role",
                admin.getRole()
        );


        String token =
                jwtUtil.generateToken(
                        admin.getEmail()
                );


        return Map.of(
                "success",
                true,
                "message",
                "Login Successful",
                "token",
                token,
                "admin",
                adminData
        );
    }
}