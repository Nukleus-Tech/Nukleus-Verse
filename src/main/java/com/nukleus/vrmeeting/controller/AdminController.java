package com.nukleus.vrmeeting.controller;

import com.nukleus.vrmeeting.model.Admin;
import com.nukleus.vrmeeting.repository.AdminRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.stream.Collectors;

import java.util.Map;
import com.nukleus.vrmeeting.repository.UserRepository;
import com.nukleus.vrmeeting.repository.MeetingRepository;
@CrossOrigin(origins = "*")

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

        if (admin == null || !admin.getPassword().trim().equals(password)) {
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
@GetMapping("/dashboard")
public Map<String, Object> getDashboard() {

    var users = userRepository.findAll();
    var meetings = meetingRepository.findAll();


    // Cards

    long totalUsers = users.size();


    long activeUsers = users.stream()
            .filter(u ->
                    u.getCurrentMeetingId() != null
                    &&
                    !u.getCurrentMeetingId().isEmpty()
            )
            .count();


    long totalMeetings = meetings.size();


    long totalRecordings = meetings.stream()
            .filter(m ->
                    m.getRecordingUrl() != null
                    &&
                    !m.getRecordingUrl().isEmpty()
            )
            .count();


    long totalNotes = meetings.stream()
            .filter(m ->
                    m.getNotesUrl() != null
                    &&
                    !m.getNotesUrl().isEmpty()
            )
            .count();



    // Recent Meetings
    List<Map<String,Object>> recentMeetings =
        meetingRepository.findTop5ByOrderByCreatedAtDesc()
        .stream()
        .map(m -> {

            Map<String,Object> data = new java.util.HashMap<>();

            data.put("meetingName", m.getMeetingName());
            data.put("hostEmail", m.getHostEmail());
            data.put("status", m.getStatus());

            return data;

        })
        .collect(Collectors.toList());





    return Map.of(

            "success", true,


            "cards", Map.of(
                    "totalUsers", totalUsers,
                    "activeUsers", activeUsers,
                    "totalMeetings", totalMeetings,
                    "recordings", totalRecordings,
                    "notes", totalNotes
            ),


            "activities", List.of(
                    "New user joined meeting room",
                    "Recording generated successfully",
                    "PDF summary created for meeting"
            ),


            "recentMeetings", recentMeetings

    );
}

}