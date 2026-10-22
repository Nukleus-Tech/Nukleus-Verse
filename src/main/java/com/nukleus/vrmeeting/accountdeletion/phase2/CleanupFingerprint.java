package com.nukleus.vrmeeting.accountdeletion.phase2;

import com.nukleus.vrmeeting.accountdeletion.AccountDeletionSafetyGateway.UserSnapshot;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;

/** Integrity binding only, NOT encryption or anonymous storage of personal data. */
public final class CleanupFingerprint {
    private CleanupFingerprint() {}
    public static String of(UserSnapshot user) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            add(digest, "nukleus-deletion-plan-v1");
            add(digest, Long.toString(user.id()));
            add(digest, user.email()); add(digest, user.googleId());
            add(digest, user.meshyTaskId()); add(digest, user.currentMeetingId());
            add(digest, Integer.toString(user.storageReferences().size()));
            for (String reference : user.storageReferences()) add(digest, reference);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
    private static void add(MessageDigest digest, String value) {
        byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(value == null ? -1 : bytes.length).array());
        digest.update(bytes);
    }
}
