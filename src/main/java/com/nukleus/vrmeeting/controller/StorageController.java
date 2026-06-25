package com.nukleus.vrmeeting.controller;

import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/storage")
public class StorageController {

    private final String bucketName = "nukleus-verse-storage";

    private final Storage storage =
            StorageOptions.getDefaultInstance().getService();

    @PostMapping("/upload-image")
    public Map<String, Object> uploadImage(@RequestParam("file") MultipartFile file) {

        try {
            if (file == null || file.isEmpty()) {
                return Map.of(
                        "success", false,
                        "message", "File is required"
                );
            }

            String originalName = file.getOriginalFilename();

            if (originalName == null || originalName.trim().isEmpty()) {
                originalName = "image.png";
            }

            String fileName =
                    "avatars/" +
                    UUID.randomUUID() +
                    "_" +
                    originalName;

            BlobInfo blobInfo =
                    BlobInfo.newBuilder(bucketName, fileName)
                            .setContentType(file.getContentType())
                            .build();

            storage.create(blobInfo, file.getBytes());

            String publicUrl =
                    "https://storage.googleapis.com/" +
                    bucketName +
                    "/" +
                    fileName;

            return Map.of(
                    "success", true,
                    "message", "Image uploaded successfully",
                    "url", publicUrl
            );

        } catch (Exception e) {
            return Map.of(
                    "success", false,
                    "message", e.getMessage()
            );
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

        // java.net.URL url = new java.net.URL(remoteUrl);
        java.net.URI uri = java.net.URI.create(remoteUrl);
        java.net.URL url = uri.toURL();

         byte[] fileBytes = url.openStream().readAllBytes();
        // byte[] fileBytes = url.openStream().readAllBytes();

        String objectName = "avatars/glb/" + UUID.randomUUID() + "_" + fileName;

        BlobInfo blobInfo = BlobInfo.newBuilder(bucketName, objectName)
                .setContentType("model/gltf-binary")
                .build();

        storage.create(blobInfo, fileBytes);

        String publicUrl = "https://storage.googleapis.com/" + bucketName + "/" + objectName;

        return Map.of(
                "success", true,
                "url", publicUrl
        );

    } catch (Exception e) {
        return Map.of(
                "success", false,
                "message", e.getMessage()
        );
    }
}
}