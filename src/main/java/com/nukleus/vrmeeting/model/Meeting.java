package com.nukleus.vrmeeting.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "meetings")
public class Meeting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "meeting_id", unique = true, nullable = false)
    private String meetingId;

    @Column(name = "room_code", nullable = false)
    private String roomCode;

    @Column(name = "host_email", nullable = false)
    private String hostEmail;

    @Column(name = "status", nullable = false)
    private String status = "ACTIVE";

    @Column(name = "meeting_name")
    private String meetingName;

    @Column(name = "recording_url", length = 2000)
    private String recordingUrl;

    // NEW: Recording Details
    @Column(name = "recording_file_name")
    private String recordingFileName;

    @Column(name = "recording_file_size")
    private String recordingFileSize;

    @Column(name = "recording_status")
    private String recordingStatus = "NOT_STARTED";

    @Column(name = "pdf_url", length = 2000)
    private String pdfUrl;

    @Column(name = "notes_url", length = 2000)
    private String notesUrl;

    @Column(name = "ppt_url", length = 2000)
    private String pptUrl;

    @Column(name = "participant_emails", length = 10000)
    private String participantEmails;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    public Meeting() {
    }

    public Long getId() {
        return id;
    }

    public String getMeetingId() {
        return meetingId;
    }

    public void setMeetingId(String meetingId) {
        this.meetingId = meetingId;
    }

    public String getRoomCode() {
        return roomCode;
    }

    public void setRoomCode(String roomCode) {
        this.roomCode = roomCode;
    }

    public String getHostEmail() {
        return hostEmail;
    }

    public void setHostEmail(String hostEmail) {
        this.hostEmail = hostEmail;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMeetingName() {
        return meetingName;
    }

    public void setMeetingName(String meetingName) {
        this.meetingName = meetingName;
    }

    public String getRecordingUrl() {
        return recordingUrl;
    }

    public void setRecordingUrl(String recordingUrl) {
        this.recordingUrl = recordingUrl;
    }

    // NEW Recording Name
    public String getRecordingFileName() {
        return recordingFileName;
    }

    public void setRecordingFileName(String recordingFileName) {
        this.recordingFileName = recordingFileName;
    }

    // NEW Recording Size
    public String getRecordingFileSize() {
        return recordingFileSize;
    }

    public void setRecordingFileSize(String recordingFileSize) {
        this.recordingFileSize = recordingFileSize;
    }

    // NEW Recording Status
    public String getRecordingStatus() {
        return recordingStatus;
    }

    public void setRecordingStatus(String recordingStatus) {
        this.recordingStatus = recordingStatus;
    }

    public String getPdfUrl() {
        return pdfUrl;
    }

    public void setPdfUrl(String pdfUrl) {
        this.pdfUrl = pdfUrl;
    }

    public String getNotesUrl() {
        return notesUrl;
    }

    public void setNotesUrl(String notesUrl) {
        this.notesUrl = notesUrl;
    }

    public String getPptUrl() {
        return pptUrl;
    }

    public void setPptUrl(String pptUrl) {
        this.pptUrl = pptUrl;
    }

    public String getParticipantEmails() {
        return participantEmails;
    }

    public void setParticipantEmails(String participantEmails) {
        this.participantEmails = participantEmails;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(LocalDateTime endedAt) {
        this.endedAt = endedAt;
    }
}