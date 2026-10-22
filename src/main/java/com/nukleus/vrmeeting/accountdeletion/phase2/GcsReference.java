package com.nukleus.vrmeeting.accountdeletion.phase2;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Comparison only: canonical URL equivalence is NOT proof of deletion ownership.
 * No network requests, redirects, DNS lookup or URL fetching occur here.
 */
public record GcsReference(String bucket, String objectName) {
    public GcsReference {
        Objects.requireNonNull(bucket); Objects.requireNonNull(objectName);
    }
    public static Optional<GcsReference> parse(String reference) {
        if (reference == null || reference.length() > 8192) return Optional.empty();
        try {
            URI uri = URI.create(reference);
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getPort() != -1 ||
                uri.getFragment() != null) return Optional.empty();
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath(); // Decode once; unlike URLDecoder, literal '+' stays '+'.
            if (path == null || !path.startsWith("/") || path.length() < 2) return Optional.empty();
            String bucket, name;
            if ("gs".equalsIgnoreCase(uri.getScheme()) && uri.getQuery() == null) {
                bucket = host; name = path.substring(1);
            } else if ("https".equalsIgnoreCase(uri.getScheme()) && host.equals("storage.googleapis.com")) {
                int slash = path.indexOf('/', 1);
                if (slash < 2 || slash == path.length() - 1) return Optional.empty();
                bucket = path.substring(1, slash); name = path.substring(slash + 1);
            } else if ("https".equalsIgnoreCase(uri.getScheme()) && host.endsWith(".storage.googleapis.com")) {
                bucket = host.substring(0, host.length() - ".storage.googleapis.com".length());
                name = path.substring(1);
            } else return Optional.empty();
            if (!bucket.matches("[a-z0-9][a-z0-9._-]{1,220}[a-z0-9]") ||
                name.isBlank() || name.indexOf('\\') >= 0 ||
                name.chars().anyMatch(c -> c < 32 || c == 127)) return Optional.empty();
            return Optional.of(new GcsReference(bucket, name));
        } catch (IllegalArgumentException invalid) { return Optional.empty(); }
    }
    @Override public String toString() { return "GcsReference[redacted]"; }
}
