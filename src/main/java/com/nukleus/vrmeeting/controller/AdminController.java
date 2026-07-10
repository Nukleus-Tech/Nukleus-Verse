package com.nukleus.vrmeeting.controller;

import com.nukleus.vrmeeting.model.Admin;
import com.nukleus.vrmeeting.model.Meeting;
import com.nukleus.vrmeeting.repository.AdminRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.stream.Collectors;
import java.time.LocalDateTime;
import java.util.HashMap;
//  import java.util.ArrayList;
import java.util.Comparator;
import java.time.Duration;
import java.time.format.DateTimeFormatter;

import java.util.Map;
import com.nukleus.vrmeeting.repository.UserRepository;
import com.nukleus.vrmeeting.repository.MeetingRepository;
import com.nukleus.vrmeeting.model.User;

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

                Map<String, Object> adminData = new java.util.HashMap<>();

                adminData.put("id", admin.getId());
                adminData.put("name", admin.getName());
                adminData.put("email", admin.getEmail());
                adminData.put("role", admin.getRole());

                return Map.of(
                                "success", true,
                                "message", "Login Successful",
                                "token", "admin-session-token",
                                "admin", adminData);
        }

        @GetMapping("/users")
        public Map<String, Object> getAllUsers() {

                var users = userRepository.findAll();
                var meetings = meetingRepository.findAll();

                // Cards

                long totalUsers = users.size();

                long activeUsers = users.stream()
                                .filter(u -> !"BLOCKED".equalsIgnoreCase(u.getAccountStatus()))
                                .count();

                long blockedUsers = users.stream()
                                .filter(u -> "BLOCKED".equalsIgnoreCase(u.getAccountStatus()))
                                .count();

                LocalDateTime oneWeekAgo = LocalDateTime.now().minusDays(7);

                long newThisWeek = users.stream()
                                .filter(u -> u.getCreatedAt() != null
                                                &&
                                                u.getCreatedAt().isAfter(oneWeekAgo))
                                .count();

                List<Map<String, Object>> userList = users.stream()
                                .map(u -> {

                                        Map<String, Object> data = new java.util.HashMap<>();

                                        data.put("id", u.getId());
                                        data.put("name", u.getName());
                                        data.put("email", u.getEmail());

                                        // Meetings count

                                        long hostedMeetings = meetings.stream()
                                                        .filter(m -> m.getHostEmail() != null &&
                                                                        m.getHostEmail()
                                                                                        .equalsIgnoreCase(u.getEmail()))
                                                        .count();

                                        long joinedMeetings = meetings.stream()
                                                        .filter(m -> m.getParticipantEmails() != null &&
                                                                        m.getParticipantEmails()
                                                                                        .toLowerCase()
                                                                                        .contains(u.getEmail()
                                                                                                        .toLowerCase()))
                                                        .count();

                                        long totalMeetings = hostedMeetings + joinedMeetings;

                                        data.put("totalMeetings", totalMeetings);
                                        data.put("hostedMeetings", hostedMeetings);
                                        data.put("joinedMeetings", joinedMeetings);

                                        data.put(
                                                        "lastLogin",
                                                        u.getLastLogin() != null
                                                                        ? u.getLastLogin()
                                                                        : "Never Login");

                                        String status = u.getAccountStatus();

                                        if (status == null || status.isEmpty()) {
                                                status = "ACTIVE";
                                        }

                                        data.put(
                                                        "status",
                                                        status);

                                        return data;

                                })
                                .collect(Collectors.toList());

                return Map.of(

                                "success", true,

                                "cards", Map.of(
                                                "totalUsers", totalUsers,
                                                "activeUsers", activeUsers,
                                                "blockedUsers", blockedUsers,
                                                "newThisWeek", newThisWeek),

                                "users", userList

                );
        }

        @GetMapping("/users/{id}")
        public Map<String, Object> getUserDetails(
                        @PathVariable Long id) {

                User user = userRepository.findById(id)
                                .orElse(null);

                if (user == null) {

                        return Map.of(
                                        "success", false,
                                        "message", "User not found");

                }

                var meetings = meetingRepository.findAll();

                long hostedMeetings = meetings.stream()
                                .filter(m -> m.getHostEmail() != null &&
                                                m.getHostEmail()
                                                                .equalsIgnoreCase(user.getEmail()))
                                .count();

                long joinedMeetings = meetings.stream()
                                .filter(m -> m.getParticipantEmails() != null &&
                                                m.getParticipantEmails()
                                                                .toLowerCase()
                                                                .contains(user.getEmail().toLowerCase()))
                                .count();

                long totalMeetings = hostedMeetings + joinedMeetings;

                List<Map<String, Object>> recentMeetings = meetings.stream()
                                .filter(m ->

                                (m.getHostEmail() != null &&
                                                m.getHostEmail()
                                                                .equalsIgnoreCase(user.getEmail()))

                                                ||

                                                (m.getParticipantEmails() != null &&
                                                                m.getParticipantEmails()
                                                                                .toLowerCase()
                                                                                .contains(user.getEmail()
                                                                                                .toLowerCase()))

                                )

                                .sorted(
                                                Comparator.comparing(
                                                                Meeting::getCreatedAt,
                                                                Comparator.nullsLast(Comparator.reverseOrder())))
                                .limit(5)

                                .map(m -> {

                                        Map<String, Object> data = new HashMap<>();
                                        data.put(
                                                        "meetingName",
                                                        m.getMeetingName() != null
                                                                        ? m.getMeetingName()
                                                                        : "Untitled Meeting");

                                        if (m.getHostEmail() != null &&
                                                        m.getHostEmail()
                                                                        .equalsIgnoreCase(user.getEmail())

                                ) {

                                                data.put(
                                                                "role",
                                                                "HOST");

                                        } else {

                                                data.put(
                                                                "role",
                                                                "PARTICIPANT");

                                        }

                                        data.put(
                                                        "status",
                                                        m.getStatus());

                                        data.put(
                                                        "createdAt",
                                                        m.getCreatedAt());

                                        return data;

                                })
                                .toList();

                Map<String, Object> avatar = new HashMap<>();

                avatar.put(
                                "avatarStatus",
                                user.getAvatarStatus());

                avatar.put(
                                "imageAvailable",
                                user.getImageUrl() != null);

                avatar.put(
                                "riggedAvatar",
                                user.getRiggedGlbUrl() != null);

                avatar.put(
                                "walkingAvatar",
                                user.getWalkingGlbUrl() != null);

                avatar.put(
                                "idleAvatar",
                                user.getIdleGlbUrl() != null);

                Map<String, Object> meetingActivity = new HashMap<>();

                meetingActivity.put(
                                "totalMeetings",
                                totalMeetings);

                meetingActivity.put(
                                "hostedMeetings",
                                hostedMeetings);

                meetingActivity.put(
                                "joinedMeetings",
                                joinedMeetings);

                Map<String, Object> userData = new HashMap<>();

                userData.put("id", user.getId());
                userData.put("name", user.getName());
                userData.put("email", user.getEmail());

                userData.put(
                                "status",
                                user.getAccountStatus() == null
                                                ? "ACTIVE"
                                                : user.getAccountStatus());

                userData.put(
                                "createdAt",
                                user.getCreatedAt());

                userData.put(
                                "lastLogin",
                                user.getLastLogin());

                userData.put(
                                "meetingActivity",
                                meetingActivity);

                userData.put(
                                "recentMeetings",
                                recentMeetings);

                userData.put(
                                "avatar",
                                avatar);

                userData.put(
                                "currentMeetingId",
                                user.getCurrentMeetingId());

                return Map.of(
                                "success",
                                true,

                                "user",
                                userData);

        }

        @PutMapping("/users/{id}/block")
        public Map<String, Object> blockUser(
                        @PathVariable Long id) {

                User user = userRepository.findById(id)
                                .orElse(null);

                if (user == null) {

                        return Map.of(
                                        "success", false,
                                        "message", "User not found");

                }

                user.setAccountStatus("BLOCKED");

                userRepository.save(user);

                return Map.of(
                                "success", true,
                                "message", "User blocked successfully");
        }

        @PutMapping("/users/{id}/unblock")
        public Map<String, Object> unblockUser(
                        @PathVariable Long id) {

                User user = userRepository.findById(id)
                                .orElse(null);

                if (user == null) {

                        return Map.of(
                                        "success", false,
                                        "message", "User not found");

                }

                user.setAccountStatus("ACTIVE");

                userRepository.save(user);

                return Map.of(
                                "success", true,
                                "message", "User unblocked successfully");
        }

        @GetMapping("/meetings")
        public Map<String, Object> getAllMeetings() {

                var meetings = meetingRepository.findAll();

                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd MMM yyyy hh:mm a");

                List<Map<String, Object>> meetingList = meetings.stream()
                                .map(m -> {

                                        Map<String, Object> data = new HashMap<>();

                                        data.put(
                                                        "meetingId",
                                                        m.getMeetingId());

                                        data.put(
                                                        "meetingName",
                                                        m.getMeetingName() != null
                                                                        ? m.getMeetingName()
                                                                        : "Untitled Meeting");
                                        data.put(
                                                        "hostEmail",
                                                        m.getHostEmail());

                                        // Participants Count

                                        int participants = 0;

                                        if (m.getParticipantEmails() != null &&
                                                        !m.getParticipantEmails().isEmpty()) {

                                                participants = m.getParticipantEmails()
                                                                .split(",").length;

                                        }

                                        data.put(
                                                        "participants",
                                                        participants);

                                        // Started

                                        data.put(
                                                        "started",
                                                        m.getCreatedAt() != null
                                                                        ? m.getCreatedAt()
                                                                                        .format(formatter)
                                                                        : "Not Available");

                                        // Duration

                                        String durationText = "In Progress";

                                        if (m.getCreatedAt() != null &&
                                                        m.getEndedAt() != null) {

                                                long minutes = Duration.between(
                                                                m.getCreatedAt(),
                                                                m.getEndedAt())
                                                                .toMinutes();

                                                durationText = minutes + " min";

                                        }

                                        data.put(
                                                        "duration",
                                                        durationText);

                                        // Status

                                        String status = m.getStatus();

                                        if ("ACTIVE".equalsIgnoreCase(status)) {

                                                status = "LIVE";

                                        } else if ("ENDED".equalsIgnoreCase(status)) {

                                                status = "COMPLETED";

                                        }

                                        data.put(
                                                        "status",
                                                        status);

                                        // Recording Status

                                        String recordingStatus;

                                        if (m.getRecordingUrl() != null &&
                                                        !m.getRecordingUrl().isEmpty()) {
                                                recordingStatus = "AVAILABLE";
                                        } else if ("ENDED".equalsIgnoreCase(m.getStatus())) {
                                                recordingStatus = "PROCESSING";
                                        } else {
                                                recordingStatus = "NOT_STARTED";
                                        }

                                        data.put(
                                                        "recording",
                                                        recordingStatus);
                                        // AI Summary Status
                                        String summaryStatus;

                                        if (m.getPdfUrl() != null &&
                                                        !m.getPdfUrl().isEmpty()) {
                                                summaryStatus = "READY";
                                        } else if ("ENDED".equalsIgnoreCase(m.getStatus())) {
                                                summaryStatus = "GENERATING";
                                        } else {
                                                summaryStatus = "NOT_STARTED";
                                        }

                                        data.put(
                                                        "summary",
                                                        summaryStatus);

                                        return data;

                                })
                                .collect(Collectors.toList());

                return Map.of(

                                "success",
                                true,

                                "meetings",
                                meetingList

                );
        }

        @GetMapping("/dashboard")
        public Map<String, Object> getDashboard() {

                var users = userRepository.findAll();
                var meetings = meetingRepository.findAll();

                // Cards

                long totalUsers = users.size();

                long activeUsers = users.stream()
                                .filter(u -> !"BLOCKED".equalsIgnoreCase(u.getAccountStatus()))
                                .count();

                long totalMeetings = meetings.size();

                long totalRecordings = meetings.stream()
                                .filter(m -> m.getRecordingUrl() != null
                                                &&
                                                !m.getRecordingUrl().isEmpty())
                                .count();

                long totalNotes = meetings.stream()
                                .filter(m -> m.getNotesUrl() != null
                                                &&
                                                !m.getNotesUrl().isEmpty())
                                .count();

                // Recent Meetings
                List<Map<String, Object>> recentMeetings = meetingRepository.findTop5ByOrderByCreatedAtDesc()
                                .stream()
                                .map(m -> {

                                        Map<String, Object> data = new java.util.HashMap<>();

                                        data.put(
                                                        "meetingName",
                                                        m.getMeetingName() != null
                                                                        ? m.getMeetingName()
                                                                        : "Untitled Meeting");
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
                                                "notes", totalNotes),

                                "activities", List.of(
                                                "New user joined meeting room",
                                                "Recording generated successfully",
                                                "PDF summary created for meeting"),

                                "recentMeetings", recentMeetings

                );
        }

        @GetMapping("/recordings")
        public Map<String, Object> getAllRecordings() {

                List<Map<String, Object>> recordings = meetingRepository.findAll()
                                .stream()
                                .filter(m -> m.getRecordingUrl() != null &&
                                                !m.getRecordingUrl().isEmpty())
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
                                "recordings", recordings);
        }

}