package com.nukleus.vrmeeting.service;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;

import org.springframework.stereotype.Service;


@Service
public class GoogleAuthService {


    public GoogleIdToken.Payload verifyToken(String idToken) throws Exception {


        GoogleIdTokenVerifier verifier =
                new GoogleIdTokenVerifier.Builder(
                        new NetHttpTransport(),
                        GsonFactory.getDefaultInstance()
                )
                .build();


        GoogleIdToken token =
                verifier.verify(idToken);


        if(token == null)
        {
            throw new Exception("Invalid Google Token");
        }


        return token.getPayload();
    }

}