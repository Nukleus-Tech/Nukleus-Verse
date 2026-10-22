package com.nukleus.vrmeeting.accountdeletion.phase3;

import com.nukleus.vrmeeting.accountdeletion.AccountDeletionException;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

/** AES-256-GCM, random nonce and user/subject AAD. No plaintext provider token in the database. */
public final class ProviderTokenCipher {
    private final CleanupIntegrationProperties properties;
    public ProviderTokenCipher(CleanupIntegrationProperties properties){this.properties=properties;}
    private Cipher cipher(int mode,byte[] nonce,long userId,String subject){
        try{
            byte[] key=Base64.getDecoder().decode(properties.getEncryptionKey());
            if(key.length!=32)throw AccountDeletionException.integration();
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
            c.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
            c.updateAAD((userId+":"+subject).getBytes(StandardCharsets.UTF_8));
            return c;
        }catch(Exception e){throw AccountDeletionException.integration();}
    }
    public byte[] encrypt(long userId,String subject,String token){
        if(token==null || token.isBlank() || token.length()>16384)throw AccountDeletionException.integration();
        byte[] nonce=new byte[12];new SecureRandom().nextBytes(nonce);
        try{
            byte[] encrypted=cipher(Cipher.ENCRYPT_MODE,nonce,userId,subject).doFinal(token.getBytes(StandardCharsets.UTF_8));
            byte[] result=Arrays.copyOf(nonce,nonce.length+encrypted.length);
            System.arraycopy(encrypted,0,result,nonce.length,encrypted.length);return result;
        }catch(Exception e){throw AccountDeletionException.integration();}
    }
    public String decrypt(long userId,String subject,byte[] encrypted){
        if(encrypted==null || encrypted.length<29)throw AccountDeletionException.integration();
        try{return new String(cipher(Cipher.DECRYPT_MODE,Arrays.copyOf(encrypted,12),userId,subject)
            .doFinal(Arrays.copyOfRange(encrypted,12,encrypted.length)),StandardCharsets.UTF_8);}
        catch(Exception e){throw AccountDeletionException.integration();}
    }
}
