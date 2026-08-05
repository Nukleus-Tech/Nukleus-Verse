package com.nukleus.vrmeeting.service;

import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URL;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class GoogleCloudStorageService {

        @Value("${gcp.bucket-name}")
        private String bucketName;

        private final Storage storage;

        public GoogleCloudStorageService(Storage storage) {
                this.storage = storage;
        }

        public String generateDownloadUrl(
                        String objectName,
                        String fileName) {
                BlobInfo blobInfo = BlobInfo.newBuilder(
                                bucketName,
                                objectName).build();

                URL signedUrl = storage.signUrl(
                                blobInfo,
                                15,
                                TimeUnit.MINUTES,
                                Storage.SignUrlOption.withV4Signature(),
                                Storage.SignUrlOption.withQueryParams(
                                                Map.of(
                                                                "response-content-disposition",
                                                                "attachment; filename=\"" + fileName + "\"")));

                return signedUrl.toString();
        }
}
