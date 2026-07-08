package com.nukleus.vrmeeting.controller;

import com.nukleus.vrmeeting.model.Admin;
import com.nukleus.vrmeeting.repository.AdminRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.stream.Collectors;
import java.time.LocalDateTime;

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


    long blockedUsers = users.stream()
            .filter(u ->
                    "BLOCKED".equalsIgnoreCase(u.getAccountStatus())
            )
            .count();


    LocalDateTime oneWeekAgo =
            LocalDateTime.now().minusDays(7);


    long newThisWeek = users.stream()
            .filter(u ->
                    u.getCreatedAt() != null
                    &&
                    u.getCreatedAt().isAfter(oneWeekAgo)
            )
            .count();



    List<Map<String,Object>> userList =
            users.stream()
            .map(u -> {

                Map<String,Object> data = new java.util.HashMap<>();

                data.put("id", u.getId());
                data.put("name", u.getName());
                data.put("email", u.getEmail());


                // Meetings count

                long meetingCount = meetings.stream()
                        .filter(m -> {

                            boolean host =
                                    m.getHostEmail() != null
                                    &&
                                    m.getHostEmail()
                                    .equalsIgnoreCase(u.getEmail());


                            boolean participant =
                                    m.getParticipantEmails() != null
                                    &&
                                    m.getParticipantEmails()
                                    .toLowerCase()
                                    .contains(
                                    u.getEmail().toLowerCase()
                                    );


                            return host || participant;

                        })
                        .count();



                data.put("meetings", meetingCount);


                data.put(
                        "lastLogin",
                        u.getLastLogin()
                );


                String status =
                        u.getAccountStatus();


                if(status == null || status.isEmpty()){
                    status = "ACTIVE";
                }


                data.put(
                        "status",
                        status
                );


                return data;

            })
            .collect(Collectors.toList());



    return Map.of(

            "success", true,


            "cards", Map.of(
                    "totalUsers", totalUsers,
                    "activeUsers", activeUsers,
                    "blockedUsers", blockedUsers,
                    "newThisWeek", newThisWeek
            ),


            "users", userList

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
@GetMapping("/recordings")
public Map<String, Object> getAllRecordings() {

    List<Map<String, Object>> recordings =
            meetingRepository.findAll()
            .stream()
            .filter(m ->
                    m.getRecordingUrl() != null &&
                    !m.getRecordingUrl().isEmpty()
            )
            .map(m -> {

                Map<String, Object> data = new java.util.HashMap<>();

                data.put("meetingId", m.getMeetingId());
                data.put("meetingName", m.getMeetingName());
                data.put("hostEmail", m.getHostEmail());
                data.put("status", m.getStatus());
                data.put("recordingUrl", m.getRecordingUrl());
                data.put("createdAt", m.getCreatedAt());
                data.put("endedAt", m.getEndedAt());

                return data;

            })
            .collect(Collectors.toList());


    return Map.of(
            "success", true,
            "recordings", recordings
    );
}

}