package com.nukleus.vrmeeting.controller;

import com.nukleus.vrmeeting.model.Meeting;
import com.nukleus.vrmeeting.model.User;
import com.nukleus.vrmeeting.repository.MeetingRepository;
import com.nukleus.vrmeeting.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

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

        if (request.getMeetingName() != null && !request.getMeetingName().trim().isEmpty()) {
            meeting.setMeetingName(request.getMeetingName().trim());
        }

        meeting.setStatus("ACTIVE");
        meeting.setRecordingStatus("NOT_STARTED");
        meeting.setCreatedAt(LocalDateTime.now());

        meetingRepository.save(meeting);

        hostUser.setCurrentMeetingId(meeting.getMeetingId());
        userRepository.save(hostUser);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Meeting created successfully");
        response.put("meetingId", meeting.getMeetingId());
        response.put("roomCode", meeting.getRoomCode());
        response.put("hostEmail", meeting.getHostEmail());
        response.put("meetingName", meeting.getMeetingName());
        response.put("status", meeting.getStatus());

        return response;
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
            return Map.of("success", false, "message", "Room code must be 3 to 8 digits only");
        }

        Meeting meeting = meetingRepository.findByRoomCodeAndStatus(roomCode, "ACTIVE");

        if (meeting == null) {
            return Map.of("success", false, "message", "No active meeting found for this room code");
        }

        User user = userRepository.findByEmailIgnoreCase(userEmail);

        if (user == null) {
            return Map.of("success", false, "message", "User not found");
        }

        user.setCurrentMeetingId(meeting.getMeetingId());
        userRepository.save(user);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Meeting joined successfully");
        response.put("meetingId", meeting.getMeetingId());
        response.put("roomCode", meeting.getRoomCode());
        response.put("meetingName", meeting.getMeetingName());
        response.put("userEmail", user.getEmail());
        response.put("status", meeting.getStatus());

        return response;
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
        meeting.setRecordingStatus("PROCESSING");

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

        List<User> meetingUsers = userRepository.findByCurrentMeetingId(meetingId);

        String participantEmails = meetingUsers.stream()
                .map(User::getEmail)
                .filter(email -> email != null && !email.trim().isEmpty())
                .distinct()
                .collect(Collectors.joining(","));

        for (User user : meetingUsers) {
            user.setCurrentMeetingId(null);
        }

        userRepository.saveAll(meetingUsers);

        meeting.setParticipantEmails(participantEmails);

        meetingRepository.save(meeting);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Meeting ended successfully");
        response.put("meetingId", meeting.getMeetingId());
        response.put("roomCode", meeting.getRoomCode());
        response.put("hostEmail", meeting.getHostEmail());
        response.put("meetingName", meeting.getMeetingName());
        response.put("participantEmails", meeting.getParticipantEmails());
        response.put("status", meeting.getStatus());
        response.put(
                "recordingStatus",
                meeting.getRecordingStatus());

        response.put("recordingUrl", meeting.getRecordingUrl());

        response.put("pdfUrl", meeting.getPdfUrl());
        response.put("notesUrl", meeting.getNotesUrl());
        response.put("pptUrl", meeting.getPptUrl());

        return response;
    }

    @PostMapping("/update-files")
    public Map<String, Object> updateMeetingFiles(@RequestBody Meeting request) {

        if (request.getMeetingId() == null || request.getMeetingId().trim().isEmpty()) {
            return Map.of("success", false, "message", "Meeting ID is required");
        }

        Meeting meeting = meetingRepository.findByMeetingId(request.getMeetingId().trim());

        if (meeting == null) {
            return Map.of("success", false, "message", "Meeting not found");
        }

        if (request.getPdfUrl() != null) {
            meeting.setPdfUrl(request.getPdfUrl());
        }

        if (request.getNotesUrl() != null) {
            meeting.setNotesUrl(request.getNotesUrl());
        }

        if (request.getRecordingUrl() != null) {
            meeting.setRecordingUrl(request.getRecordingUrl());
        }
        if (request.getRecordingStatus() != null) {
            meeting.setRecordingStatus(
                    request.getRecordingStatus());
        }

        if (request.getPptUrl() != null) {
            meeting.setPptUrl(request.getPptUrl());
        }

        meetingRepository.save(meeting);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Meeting files updated successfully");
        response.put("meetingId", meeting.getMeetingId());
        response.put("pdfUrl", meeting.getPdfUrl());
        response.put("recordingUrl", meeting.getRecordingUrl());
        response.put("notesUrl", meeting.getNotesUrl());
        response.put("pptUrl", meeting.getPptUrl());
        response.put(
                "recordingStatus",
                meeting.getRecordingStatus());

        return response;
    }

    @PostMapping("/leave")
    public Map<String, Object> leaveMeeting(@RequestBody Map<String, String> request) {

        String userEmail = request.get("userEmail");

        if (userEmail == null || userEmail.trim().isEmpty()) {
            return Map.of(
                    "success", false,
                    "message", "User email is required");
        }

        User user = userRepository.findByEmailIgnoreCase(
                userEmail.trim());

        if (user == null) {
            return Map.of(
                    "success", false,
                    "message", "User not found");
        }

        user.setCurrentMeetingId(null);

        userRepository.save(user);

        return Map.of(
                "success", true,
                "message", "User left meeting successfully",
                "userEmail", user.getEmail());
    }
}