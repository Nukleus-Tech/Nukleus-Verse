package com.nukleus.vrmeeting.accountdeletion.phase3;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Backward-compatible legacy verification; successful opt-in login upgrades plaintext in place. */
public final class PasswordCredentials {
    private static final BCryptPasswordEncoder BCRYPT=new BCryptPasswordEncoder(12);
    private PasswordCredentials(){}
    public static boolean matches(String supplied,String stored) {
        if(supplied==null || stored==null) return false;
        if(stored.startsWith("{bcrypt-sha256}")) {
            try{return BCRYPT.matches(prehash(supplied),stored.substring(15));}catch(IllegalArgumentException e){return false;}
        }
        if(stored.startsWith("{bcrypt}")) {
            try{return BCRYPT.matches(supplied,stored.substring(8));}catch(IllegalArgumentException e){return false;}
        }
        return MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8),stored.getBytes(StandardCharsets.UTF_8));
    }
    public static String encode(String supplied){return "{bcrypt-sha256}"+BCRYPT.encode(prehash(supplied));}
    public static boolean needsUpgrade(String stored){
        return stored!=null && !stored.startsWith("{bcrypt-sha256}") && !stored.startsWith("{bcrypt}");
    }
    private static String prehash(String supplied) {
        try {
            return java.util.Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(supplied.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
