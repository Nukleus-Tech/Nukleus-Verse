package com.nukleus.vrmeeting.accountdeletion.phase3;

import com.google.cloud.storage.*;
import com.nukleus.vrmeeting.accountdeletion.AccountDeletionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.net.*;
import java.util.*;

/** New personal objects only. Shared meeting uploads stay outside this ownership registry. */
public final class OwnedUploadRegistry {
    private static final int MAX_BYTES = 64 * 1024 * 1024;
    private final JdbcTemplate jdbc;
    private final ObjectProvider<Storage> storage;
    private final AuthenticatedRouteGuard guard;
    private final Phase3Properties properties;
    public OwnedUploadRegistry(JdbcTemplate jdbc, ObjectProvider<Storage> storage,
                               AuthenticatedRouteGuard guard, Phase3Properties properties) {
        this.jdbc = jdbc; this.storage = storage; this.guard = guard; this.properties = properties;
    }
    public boolean enabled() { return properties.isEnabled(); }
    public Map<String, Object> uploadPhoto(String bucket, MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > MAX_BYTES) throw invalid();
        try { return upload(bucket, "PHOTO", file.getContentType(), file.getBytes()); }
        catch (IOException e) { throw unavailable(); }
    }
    public Map<String, Object> archive(String bucket, Map<String, String> body) {
        guard.currentUserId(); // Check before fetching any remote data.
        URI uri = validatedArchiveUri(body.get("remoteUrl"));
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(30_000);
            if (connection.getResponseCode() != 200) throw unavailable();
            try (var stream = connection.getInputStream()) {
                byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
                if (bytes.length == 0 || bytes.length > MAX_BYTES) throw invalid();
                return upload(bucket, "AVATAR", "model/gltf-binary", bytes);
            }
        } catch (IOException e) { throw unavailable(); }
        finally { if (connection != null) connection.disconnect(); }
    }
    public URI validatedArchiveUri(String source) {
        try {
            URI uri = URI.create(source);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getRawUserInfo() != null ||
                    (uri.getPort() != -1 && uri.getPort() != 443) || uri.getFragment() != null ||
                    !properties.getArchiveHosts().contains(host.toLowerCase(Locale.ROOT))) throw invalid();
            // Allow only explicitly reviewed provider-controlled DNS names, not arbitrary user hosts.
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() ||
                        address.isSiteLocalAddress() || address.isMulticastAddress() ||
                        ((address.getAddress()[0] & 0xfe) == 0xfc && address.getAddress().length == 16)) throw invalid();
            }
            return uri;
        } catch (IllegalArgumentException | NullPointerException | UnknownHostException e) { throw invalid(); }
    }
    private Map<String, Object> upload(String bucket, String kind, String contentType, byte[] data) {
        long userId = guard.currentUserId();
        String name = "users/" + userId + "/" + kind.toLowerCase(Locale.ROOT) + "/" + UUID.randomUUID() +
            ("AVATAR".equals(kind) ? ".glb" : photoExtension(contentType));
        // Reserve first. A crash or upload/journal failure leaves evidence requiring manual reconciliation.
        jdbc.update("insert into account_deletion_owned_objects(bucket,object_name,user_id,kind) values (?,?,?,?)",
            bucket, name, userId, kind);
        BlobInfo info = BlobInfo.newBuilder(bucket, name).setContentType(
            contentType == null ? "application/octet-stream" : contentType)
            .setMetadata(Map.of("nukleusUserId", Long.toString(userId), "nukleusExclusiveOwner", "true")).build();
        try {
            Blob blob = storage.getObject().create(info, data, Storage.BlobTargetOption.doesNotExist());
            if (blob == null || blob.getGeneration() == null || blob.getGeneration() <= 0 ||
                    !bucket.equals(blob.getBucket()) || !name.equals(blob.getName())) throw unavailable();
            int recorded = jdbc.update("""
                update account_deletion_owned_objects set generation=?
                where bucket=? and object_name=? and user_id=? and generation is null
                """, blob.getGeneration(), bucket, name, userId);
            if (recorded != 1) throw unavailable();
        } catch (StorageException e) { throw unavailable(); }
        String url = "https://storage.googleapis.com/" + bucket + "/" + name;
        return Map.of("success", true, "message", "Image uploaded successfully",
            "url", url, "fileUrl", url, "fileName", name);
    }
    private static AccountDeletionException invalid() {
        return new AccountDeletionException(HttpStatus.BAD_REQUEST, "INVALID_PERSONAL_ASSET");
    }
    private static String photoExtension(String contentType) {
        if ("image/png".equalsIgnoreCase(contentType)) return ".png";
        if ("image/jpeg".equalsIgnoreCase(contentType)) return ".jpg";
        if ("image/webp".equalsIgnoreCase(contentType)) return ".webp";
        return ".image";
    }
    private static AccountDeletionException unavailable() {
        return new AccountDeletionException(HttpStatus.SERVICE_UNAVAILABLE, "PERSONAL_ASSET_UNAVAILABLE");
    }
}
