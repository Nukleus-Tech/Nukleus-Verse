package com.nukleus.vrmeeting.service;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;

import org.springframework.stereotype.Service;


@Service
public class GoogleAuthService {

    private final GoogleIdTokenVerifier verifier;
    public GoogleAuthService(GoogleIdTokenVerifier verifier) { this.verifier = verifier; }


    public GoogleIdToken.Payload verifyToken(String idToken) throws Exception {


        if (idToken == null || idToken.length() > 16384 ||
                verifier.getAudience() == null || verifier.getAudience().isEmpty()) {
            throw new Exception("Google authentication is not configured");
        }


        GoogleIdToken token =
                verifier.verify(idToken);


        if(token == null || token.getPayload().getSubject() == null ||
                token.getPayload().getSubject().isBlank() || !Boolean.TRUE.equals(token.getPayload().getEmailVerified()))
        {
            throw new Exception("Invalid Google Token");
        }


        return token.getPayload();
    }

}