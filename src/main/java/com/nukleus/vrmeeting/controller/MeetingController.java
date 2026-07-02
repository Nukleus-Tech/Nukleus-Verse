package com.nukleus.vrmeeting.controller;

import com.nukleus.vrmeeting.model.Meeting;
import com.nukleus.vrmeeting.repository.MeetingRepository;
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

    @PostMapping("/create")
    public Map<String, Object> createMeeting(@RequestBody Meeting request) {

        if (request.getRoomCode() == null || request.getRoomCode().trim().isEmpty()) {
            return Map.of("success", false, "message", "Room code is required");
        }

        if (request.getHostEmail() == null || request.getHostEmail().trim().isEmpty()) {
            return Map.of("success", false, "message", "Host email is required");
        }

        String roomCode = request.getRoomCode().trim();

        if (!roomCode.matches("^[0-9]{3,8}$")) {
            return Map.of(
                    "success", false,
                    "message", "Room code must be 3 to 8 digits only"
            );
        }

        String hostEmail = request.getHostEmail().trim().toLowerCase();

        Meeting activeMeeting =
                meetingRepository.findByRoomCodeAndStatus(roomCode, "ACTIVE");

        if (activeMeeting != null) {
            return Map.of(
                    "success", false,
                    "message", "This room code is already in use"
            );
        }

        Meeting meeting = new Meeting();
        meeting.setMeetingId(UUID.randomUUID().toString());
        meeting.setRoomCode(roomCode);
        meeting.setHostEmail(hostEmail);
        meeting.setStatus("ACTIVE");
        meeting.setCreatedAt(LocalDateTime.now());

        meetingRepository.save(meeting);

        return Map.of(
                "success", true,
                "message", "Meeting created successfully",
                "meetingId", meeting.getMeetingId(),
                "roomCode", meeting.getRoomCode(),
                "hostEmail", meeting.getHostEmail(),
                "status", meeting.getStatus()
        );
    }
}