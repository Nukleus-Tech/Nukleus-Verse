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


    public String generateDownloadUrl(String fileName) {

        BlobInfo blobInfo = BlobInfo.newBuilder(
                bucketName,
                fileName
        ).build();


        URL url = storage.signUrl(
                blobInfo,
                1,
                TimeUnit.HOURS,
                Storage.SignUrlOption.withV4Signature()
        );


        return url.toString();
    }
}