

package com.nukleus.vrmeeting.controller;

import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;


@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/admin")
public class AdminController {


    // =====================================
    // GET ADMIN PROFILE
    // =====================================

    @GetMapping("/settings/profile")
    public Map<String, Object> getAdminProfile() {


        Map<String, Object> admin =
                new HashMap<>();


        admin.put(
                "name",
                "Super Admin"
        );


        admin.put(
                "email",
                "admin@nukleus.work"
        );


        admin.put(
                "role",
                "SUPER_ADMIN"
        );


        admin.put(
                "status",
                "ACTIVE"
        );


        return Map.of(
                "success",
                true,

                "admin",
                admin
        );

    }



    // =====================================
    // GET SYSTEM INFORMATION
    // =====================================

    @GetMapping("/settings/system")
    public Map<String, Object> getSystemInformation() {


        Map<String, Object> system =
                new HashMap<>();


        system.put(
                "application",
                "Nukleus-verse"
        );


        system.put(
                "backend",
                "Spring Boot 3.5.3"
        );


        system.put(
                "database",
                "PostgreSQL"
        );


        system.put(
                "storage",
                "Google Cloud Storage"
        );


        system.put(
                "version",
                "1.0"
        );


        return Map.of(
                "success",
                true,

                "system",
                system
        );

    }

}