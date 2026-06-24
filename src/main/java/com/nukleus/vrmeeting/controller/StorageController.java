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
}