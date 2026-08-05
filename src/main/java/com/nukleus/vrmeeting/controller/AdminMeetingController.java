package com.nukleus.vrmeeting.controller;

import com.nukleus.vrmeeting.model.Meeting;
import com.nukleus.vrmeeting.repository.MeetingRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import java.util.*;
import java.util.stream.Collectors;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/admin")
public class AdminMeetingController {

        @Autowired
        private MeetingRepository meetingRepository;

        // ==============================
        // Duration Helper
        // ==============================

        private String calculateDuration(
                        LocalDateTime start,
                        LocalDateTime end) {

                if (start == null || end == null) {
                        return "Not Available";
                }

                long seconds = Duration.between(start, end)
                                .getSeconds();

                long minutes = seconds / 60;

                if (minutes <= 0) {
                        return "Less than 1 min";
                }

                if (minutes < 60) {
                        return minutes + " min";
                }

                long hours = minutes / 60;

                long remainingMinutes = minutes % 60;

                if (remainingMinutes == 0) {

                        return hours +
                                        (hours == 1
                                                        ? " hour"
                                                        : " hours");
                }

                return hours +
                                (hours == 1
                                                ? " hour "
                                                : " hours ")
                                +
                                remainingMinutes
                                +
                                " min";
        }

        // ==============================
        // GET ALL MEETINGS
        // ==============================

        @GetMapping("/meetings")
        public Map<String, Object> getAllMeetings() {

                List<Meeting> meetings = meetingRepository.findAll();

                long totalMeetings = meetings.size();

                long liveMeetings = meetings.stream()
                                .filter(m -> "ACTIVE"
                                                .equalsIgnoreCase(
                                                                m.getStatus()))
                                .count();

                long completedMeetings = meetings.stream()
                                .filter(m -> "ENDED"
                                                .equalsIgnoreCase(
                                                                m.getStatus()))
                                .count();

                long todayMeetings = meetingRepository
                                .countTodayMeetings();

                meetings.sort(
                                Comparator
                                                .comparing(
                                                                (Meeting m) -> "ACTIVE"
                                                                                .equalsIgnoreCase(
                                                                                                m.getStatus())
                                                                                                                ? 0
                                                                                                                : 1)
                                                .thenComparing(
                                                                Meeting::getCreatedAt,
                                                                Comparator.nullsLast(
                                                                                Comparator.reverseOrder())));

                DateTimeFormatter formatter = DateTimeFormatter.ofPattern(
                                "dd MMM yyyy hh:mm a");

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

                                        if (m.getParticipantEmails() != null
                                                        &&
                                                        !m.getParticipantEmails()
                                                                        .trim()
                                                                        .isEmpty()) {

                                                participants = m.getParticipantEmails()
                                                                .split(",").length;
                                        }

                                        data.put(
                                                        "participants",
                                                        participants);

                                        data.put(
                                                        "started",
                                                        m.getCreatedAt() != null
                                                                        ? m.getCreatedAt()
                                                                                        .format(formatter)
                                                                        : "Not Available");

                                        data.put(
                                                        "duration",
                                                        calculateDuration(
                                                                        m.getCreatedAt(),
                                                                        m.getEndedAt()));

                                        String status = m.getStatus();

                                        if ("ACTIVE"
                                                        .equalsIgnoreCase(status)) {

                                                status = "LIVE";

                                        } else if ("ENDED"
                                                        .equalsIgnoreCase(status)) {

                                                status = "COMPLETED";
                                        }

                                        data.put(
                                                        "status",
                                                        status);

                                        String recordingStatus;

                                        if (m.getRecordingUrl() != null
                                                        &&
                                                        !m.getRecordingUrl()
                                                                        .isEmpty()) {

                                                recordingStatus = "AVAILABLE";

                                        } else if ("ENDED"
                                                        .equalsIgnoreCase(
                                                                        m.getStatus())) {

                                                recordingStatus = "PROCESSING";

                                        } else {

                                                recordingStatus = "NOT_STARTED";
                                        }

                                        data.put(
                                                        "recording",
                                                        recordingStatus);

                                        data.put(
                                                        "summary",
                                                        "NOT_AVAILABLE");

                                        return data;

                                })
                                .collect(Collectors.toList());

                return Map.of(

                                "success",
                                true,

                                "cards",
                                Map.of(
                                                "totalMeetings",
                                                totalMeetings,

                                                "liveMeetings",
                                                liveMeetings,

                                                "completedMeetings",
                                                completedMeetings,

                                                "todayMeetings",
                                                todayMeetings),

                                "meetings",
                                meetingList

                );

        }

        // ==============================
        // GET SINGLE MEETING DETAILS
        // ==============================

        @GetMapping("/meetings/{meetingId}")
        public Map<String, Object> getMeetingDetails(
                        @PathVariable String meetingId) {

                Meeting meeting = meetingRepository
                                .findByMeetingId(meetingId);

                if (meeting == null) {

                        return Map.of(
                                        "success",
                                        false,
                                        "message",
                                        "Meeting not found");
                }

                Map<String, Object> data = new HashMap<>();

                data.put(
                                "meetingId",
                                meeting.getMeetingId());

                data.put(
                                "meetingName",
                                meeting.getMeetingName());

                data.put(
                                "hostEmail",
                                meeting.getHostEmail());

                data.put(
                                "roomCode",
                                meeting.getRoomCode());

                int participants = 0;

                List<String> participantList = new ArrayList<>();

                if (meeting.getParticipantEmails() != null
                                &&
                                !meeting.getParticipantEmails()
                                                .trim()
                                                .isEmpty()) {

                        participantList = Arrays.stream(
                                        meeting.getParticipantEmails()
                                                        .split(","))
                                        .map(String::trim)
                                        .filter(e -> !e.isEmpty())
                                        .collect(Collectors.toList());

                        participants = participantList.size();
                }

                data.put(
                                "participants",
                                participants);

                data.put(
                                "participantEmails",
                                participantList);

                String status = meeting.getStatus();

                if ("ACTIVE".equalsIgnoreCase(status)) {

                        status = "LIVE";

                } else if ("ENDED"
                                .equalsIgnoreCase(status)) {

                        status = "COMPLETED";
                }

                data.put(
                                "status",
                                status);

                data.put(
                                "createdAt",
                                meeting.getCreatedAt());

                data.put(
                                "endedAt",
                                meeting.getEndedAt());

                data.put(
                                "duration",
                                calculateDuration(
                                                meeting.getCreatedAt(),
                                                meeting.getEndedAt()));

                data.put(
                                "recordingUrl",
                                meeting.getRecordingUrl());

                data.put(
                                "pdfUrl",
                                meeting.getPdfUrl());

                data.put(
                                "pptUrl",
                                meeting.getPptUrl());

                data.put(
                                "notesUrl",
                                meeting.getNotesUrl());

                return Map.of(
                                "success",
                                true,

                                "meeting",
                                data);

        }
        // ==============================
        // DOWNLOAD PDF
        // ==============================

        @GetMapping("/meetings/{meetingId}/pdf/download")
        public ResponseEntity<?> downloadPdf(
                        @PathVariable String meetingId) {

                Meeting meeting = meetingRepository.findByMeetingId(meetingId);

                if (meeting == null) {

                        return ResponseEntity.notFound().build();
                }

                if (meeting.getPdfUrl() == null ||
                                meeting.getPdfUrl().isEmpty()) {

                        return ResponseEntity.notFound().build();
                }

                RestTemplate restTemplate = new RestTemplate();

                byte[] pdfFile = restTemplate.getForObject(
                                meeting.getPdfUrl(),
                                byte[].class);

                return ResponseEntity.ok()
                                .header(
                                                HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=\"meeting.pdf\"")
                                .contentType(
                                                MediaType.APPLICATION_PDF)
                                .body(pdfFile);

        }
}