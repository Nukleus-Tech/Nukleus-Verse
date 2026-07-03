package com.nukleus.vrmeeting.controller;

import com.nukleus.vrmeeting.model.Meeting;
import com.nukleus.vrmeeting.model.User;
import com.nukleus.vrmeeting.repository.MeetingRepository;
import com.nukleus.vrmeeting.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/meeting")
public class MeetingController {

    @Autowired
    private MeetingRepository meetingRepository;

    @Autowired
    private UserRepository userRepository;

    @PostMapping("/create")
    public Map<String, Object> createMeeting(@RequestBody Meeting request) {

        if (request.getRoomCode() == null || request.getRoomCode().trim().isEmpty()) {
            return Map.of("success", false, "message", "Room code is required");
        }

        if (request.getHostEmail() == null || request.getHostEmail().trim().isEmpty()) {
            return Map.of("success", false, "message", "Host email is required");
        }

        String roomCode = request.getRoomCode().trim();
        String hostEmail = request.getHostEmail().trim().toLowerCase();

        if (!roomCode.matches("^[0-9]{3,8}$")) {
            return Map.of("success", false, "message", "Room code must be 3 to 8 digits only");
        }

        User hostUser = userRepository.findByEmailIgnoreCase(hostEmail);

        if (hostUser == null) {
            return Map.of("success", false, "message", "Host user not found");
        }

        Meeting activeMeeting = meetingRepository.findByRoomCodeAndStatus(roomCode, "ACTIVE");

        if (activeMeeting != null) {
            return Map.of("success", false, "message", "This room code is already in use");
        }

        Meeting meeting = new Meeting();
        meeting.setMeetingId(UUID.randomUUID().toString());
        meeting.setRoomCode(roomCode);
        meeting.setHostEmail(hostEmail);
        meeting.setStatus("ACTIVE");
        meeting.setCreatedAt(LocalDateTime.now());

        meetingRepository.save(meeting);

        hostUser.setCurrentMeetingId(meeting.getMeetingId());
        userRepository.save(hostUser);

        return Map.of(
                "success", true,
                "message", "Meeting created successfully",
                "meetingId", meeting.getMeetingId(),
                "roomCode", meeting.getRoomCode(),
                "hostEmail", meeting.getHostEmail(),
                "status", meeting.getStatus()
        );
    }
    @PostMapping("/join")
public Map<String, Object> joinMeeting(@RequestBody Map<String, String> request) {

    String roomCode = request.get("roomCode");
    String userEmail = request.get("userEmail");

    if (roomCode == null || roomCode.trim().isEmpty()) {
        return Map.of("success", false, "message", "Room code is required");
    }

    if (userEmail == null || userEmail.trim().isEmpty()) {
        return Map.of("success", false, "message", "User email is required");
    }

    roomCode = roomCode.trim();
    userEmail = userEmail.trim().toLowerCase();

    if (!roomCode.matches("^[0-9]{3,8}$")) {
        return Map.of(
                "success", false,
                "message", "Room code must be 3 to 8 digits only"
        );
    }

    Meeting meeting = meetingRepository.findByRoomCodeAndStatus(roomCode, "ACTIVE");

    if (meeting == null) {
        return Map.of(
                "success", false,
                "message", "No active meeting found for this room code"
        );
    }

    User user = userRepository.findByEmailIgnoreCase(userEmail);

    if (user == null) {
        return Map.of(
                "success", false,
                "message", "User not found"
        );
    }

    user.setCurrentMeetingId(meeting.getMeetingId());
    userRepository.save(user);

    return Map.of(
            "success", true,
            "message", "Meeting joined successfully",
            "meetingId", meeting.getMeetingId(),
            "roomCode", meeting.getRoomCode(),
            "userEmail", user.getEmail(),
            "status", meeting.getStatus()
    );
 }
 @PostMapping("/end")
public Map<String, Object> endMeeting(@RequestBody Meeting request) {

    if (request.getMeetingId() == null || request.getMeetingId().trim().isEmpty()) {
        return Map.of("success", false, "message", "Meeting ID is required");
    }

    String meetingId = request.getMeetingId().trim();

    Meeting meeting = meetingRepository.findByMeetingId(meetingId);

    if (meeting == null) {
        return Map.of("success", false, "message", "Meeting not found");
    }

    meeting.setStatus("ENDED");
    meeting.setEndedAt(LocalDateTime.now());

    if (request.getRecordingUrl() != null) {
        meeting.setRecordingUrl(request.getRecordingUrl());
    }

    if (request.getPdfUrl() != null) {
        meeting.setPdfUrl(request.getPdfUrl());
    }

    if (request.getNotesUrl() != null) {
        meeting.setNotesUrl(request.getNotesUrl());
    }

    if (request.getPptUrl() != null) {
        meeting.setPptUrl(request.getPptUrl());
    }

    meetingRepository.save(meeting);

    return Map.of(
            "success", true,
            "message", "Meeting ended successfully",
            "meetingId", meeting.getMeetingId(),
            "roomCode", meeting.getRoomCode(),
            "hostEmail", meeting.getHostEmail(),
            "status", meeting.getStatus(),
            "recordingUrl", meeting.getRecordingUrl(),
            "pdfUrl", meeting.getPdfUrl(),
            "notesUrl", meeting.getNotesUrl(),
            "pptUrl", meeting.getPptUrl()
    );
}
}