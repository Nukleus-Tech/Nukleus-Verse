package com.nukleus.vrmeeting.controller;

import com.nukleus.vrmeeting.accountdeletion.phase3.GoogleReauthentication;
import com.nukleus.vrmeeting.dto.GoogleLoginRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class GoogleAccountController {
    private final GoogleReauthentication google;
    public GoogleAccountController(GoogleReauthentication google){this.google=google;}
    @PostMapping("/google-challenge")
    public ResponseEntity<Map<String,Object>> challenge(){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(google.challenge());
    }
    @PostMapping("/google")
    public ResponseEntity<Map<String,Object>> login(@RequestBody GoogleLoginRequest request){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(google.login(request.getIdToken()));
    }
}
