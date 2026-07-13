package com.nukleus.vrmeeting.controller;

import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.nukleus.vrmeeting.model.Meeting;
import com.nukleus.vrmeeting.repository.MeetingRepository;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/storage")
public class StorageController {
    @Autowired
    private MeetingRepository meetingRepository;

    private final String bucketName = "nukleus-verse-storage";

    private final Storage storage = StorageOptions.getDefaultInstance().getService();

    @PostMapping("/upload-image")
    public Map<String, Object> uploadImage(@RequestParam("file") MultipartFile file) {
        return uploadToGcs(file, "images/", "Image uploaded successfully");
    }

    @PostMapping("/upload-meeting-file")
    public Map<String, Object> uploadMeetingFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("meetingId") String meetingId,
            @RequestParam("fileType") String fileType) {
        try {
            if (meetingId == null || meetingId.trim().isEmpty()) {
                return Map.of("success", false, "message", "meetingId is required");
            }

            if (fileType == null || fileType.trim().isEmpty()) {
                return Map.of("success", false, "message", "fileType is required");
            }

            String safeType = fileType.trim().toLowerCase();
            String safeMeetingFolder = "meeting-id-" + meetingId.trim();

            String folder = "meetings/" + safeMeetingFolder + "/" + safeType + "/";
            Map<String, Object> response = new java.util.HashMap<>(
                    uploadToGcs(
                            file,
                            folder,
                            "Meeting file uploaded successfully"));

            if (Boolean.TRUE.equals(response.get("success"))) {

                String url = (String) response.get("url");

                Meeting meeting = meetingRepository.findByMeetingId(
                        meetingId.trim());

                if (meeting != null) {

                    switch (safeType) {

                        case "recording":

                            meeting.setRecordingUrl(url);

                            meeting.setRecordingFileName(
                                    file.getOriginalFilename());

                            double sizeMB = file.getSize()
                                    /
                                    (1024.0 * 1024.0);

                            meeting.setRecordingFileSize(
                                    String.format("%.2f MB", sizeMB));
                            meeting.setRecordingStatus("AVAILABLE");

                            response.put(
                                    "recordingStatus",
                                    "AVAILABLE");

                            break;

                        case "pdf":

                            meeting.setPdfUrl(url);
                            break;

                        case "notes":

                            meeting.setNotesUrl(url);
                            break;

                        case "ppt":

                            meeting.setPptUrl(url);
                            break;
                    }

                    meetingRepository.save(meeting);

                }

            }

            return response;

        } catch (Exception e) {
            return Map.of("success", false, "message", e.getMessage());
        }
    }

    private Map<String, Object> uploadToGcs(
            MultipartFile file,
            String folder,
            String successMessage) {
        try {
            if (file == null || file.isEmpty()) {
                return Map.of("success", false, "message", "File is required");
            }

            String originalName = file.getOriginalFilename();

            if (originalName == null || originalName.trim().isEmpty()) {
                originalName = "file";
            }

            String fileName = folder +
                    UUID.randomUUID() +
                    "_" +
                    originalName;

            BlobInfo blobInfo = BlobInfo.newBuilder(bucketName, fileName)
                    .setContentType(file.getContentType())
                    .build();

            storage.create(blobInfo, file.getBytes());

            String publicUrl = "https://storage.googleapis.com/" +
                    bucketName +
                    "/" +
                    fileName;

            return Map.of(
                    "success", true,
                    "message", successMessage,
                    "url", publicUrl,
                    "fileUrl", publicUrl);

        } catch (Exception e) {
            return Map.of("success", false, "message", e.getMessage());
        }
    }

    @PostMapping("/archive-remote-file")
    public Map<String, Object> archiveRemoteFile(@RequestBody Map<String, String> body) {
        try {
            String remoteUrl = body.get("remoteUrl");
            String fileName = body.get("fileName");

            if (remoteUrl == null || remoteUrl.isEmpty()) {
                return Map.of("success", false, "message", "remoteUrl is required");
            }

            if (fileName == null || fileName.isEmpty()) {
                fileName = UUID.randomUUID() + ".glb";
            }

            java.net.URI uri = java.net.URI.create(remoteUrl);
            java.net.URL url = uri.toURL();

            byte[] fileBytes = url.openStream().readAllBytes();

            String objectName = "images/glb/" + UUID.randomUUID() + "_" + fileName;

            BlobInfo blobInfo = BlobInfo.newBuilder(bucketName, objectName)
                    .setContentType("model/gltf-binary")
                    .build();

            storage.create(blobInfo, fileBytes);

            String publicUrl = "https://storage.googleapis.com/" +
                    bucketName +
                    "/" +
                    objectName;

            return Map.of(
                    "success", true,
                    "url", publicUrl,
                    "fileUrl", publicUrl);

        } catch (Exception e) {
            return Map.of("success", false, "message", e.getMessage());
        }
    }
}