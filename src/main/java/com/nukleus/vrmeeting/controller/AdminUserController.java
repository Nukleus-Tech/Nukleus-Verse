package com.nukleus.vrmeeting.controller;

import com.nukleus.vrmeeting.model.User;
import com.nukleus.vrmeeting.model.Meeting;
import com.nukleus.vrmeeting.repository.UserRepository;
import com.nukleus.vrmeeting.repository.MeetingRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;


@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/admin")
public class AdminUserController {


    @Autowired
    private UserRepository userRepository;


    @Autowired
    private MeetingRepository meetingRepository;



    // ============================
    // GET ALL USERS
    // ============================

    @GetMapping("/users")
    public Map<String,Object> getAllUsers() {


        List<User> users =
                userRepository.findAll();


        List<Meeting> meetings =
                meetingRepository.findAll();



        long totalUsers =
                users.size();



        long activeUsers =
                users.stream()
                        .filter(u ->
                                !"BLOCKED".equalsIgnoreCase(
                                        u.getAccountStatus()))
                        .count();



        long blockedUsers =
                users.stream()
                        .filter(u ->
                                "BLOCKED".equalsIgnoreCase(
                                        u.getAccountStatus()))
                        .count();



        LocalDateTime oneWeekAgo =
                LocalDateTime.now(
                        ZoneId.of("Asia/Kolkata"))
                        .minusDays(7);



        long newThisWeek =
                users.stream()
                        .filter(u ->
                                u.getCreatedAt()!=null
                                &&
                                u.getCreatedAt()
                                        .isAfter(oneWeekAgo))
                        .count();



        List<Map<String,Object>> userList =
                users.stream()
                        .map(u -> {


                            Map<String,Object> data =
                                    new HashMap<>();


                            data.put(
                                    "id",
                                    u.getId());


                            data.put(
                                    "name",
                                    u.getName());


                            data.put(
                                    "email",
                                    u.getEmail());



                            long hostedMeetings =
                                    meetings.stream()
                                            .filter(m ->
                                                    m.getHostEmail()!=null
                                                    &&
                                                    m.getHostEmail()
                                                    .equalsIgnoreCase(
                                                            u.getEmail()))
                                            .count();



                            long joinedMeetings =
                                    meetings.stream()
                                            .filter(m ->
                                                    m.getParticipantEmails()!=null
                                                    &&
                                                    m.getParticipantEmails()
                                                    .toLowerCase()
                                                    .contains(
                                                    u.getEmail()
                                                    .toLowerCase()))
                                            .count();



                            data.put(
                                    "totalMeetings",
                                    hostedMeetings + joinedMeetings);



                            data.put(
                                    "hostedMeetings",
                                    hostedMeetings);



                            data.put(
                                    "joinedMeetings",
                                    joinedMeetings);



                            data.put(
                                    "lastLogin",
                                    u.getLastLogin()!=null
                                    ?
                                    u.getLastLogin()
                                    :
                                    "Never Login");



                            String status =
                                    u.getAccountStatus();



                            if(status==null ||
                                    status.isEmpty()) {

                                status="ACTIVE";
                            }


                            data.put(
                                    "status",
                                    status);



                            return data;


                        })
                        .collect(Collectors.toList());




        return Map.of(

                "success",
                true,


                "cards",
                Map.of(
                        "totalUsers",
                        totalUsers,

                        "activeUsers",
                        activeUsers,

                        "blockedUsers",
                        blockedUsers,

                        "newThisWeek",
                        newThisWeek
                ),


                "users",
                userList

        );

    }





    // ============================
    // USER DETAILS
    // ============================


    @GetMapping("/users/{id}")
    public Map<String,Object> getUserDetails(
            @PathVariable Long id) {



        User user =
                userRepository.findById(id)
                        .orElse(null);



        if(user==null){

            return Map.of(
                    "success",
                    false,
                    "message",
                    "User not found"
            );
        }



        List<Meeting> meetings =
                meetingRepository.findAll();



        long hostedMeetings =
                meetings.stream()
                        .filter(m ->
                                m.getHostEmail()!=null
                                &&
                                m.getHostEmail()
                                .equalsIgnoreCase(
                                        user.getEmail()))
                        .count();



        long joinedMeetings =
                meetings.stream()
                        .filter(m ->
                                m.getParticipantEmails()!=null
                                &&
                                m.getParticipantEmails()
                                .toLowerCase()
                                .contains(
                                user.getEmail()
                                .toLowerCase()))
                        .count();



        Map<String,Object> activity =
                new HashMap<>();


        activity.put(
                "totalMeetings",
                hostedMeetings + joinedMeetings);


        activity.put(
                "hostedMeetings",
                hostedMeetings);


        activity.put(
                "joinedMeetings",
                joinedMeetings);



        Map<String,Object> avatar =
                new HashMap<>();


        avatar.put(
                "avatarStatus",
                user.getAvatarStatus());


        avatar.put(
                "imageAvailable",
                user.getImageUrl()!=null);


        avatar.put(
                "riggedAvatar",
                user.getRiggedGlbUrl()!=null);


        avatar.put(
                "walkingAvatar",
                user.getWalkingGlbUrl()!=null);


        avatar.put(
                "idleAvatar",
                user.getIdleGlbUrl()!=null);



        Map<String,Object> data =
                new HashMap<>();


        data.put(
                "id",
                user.getId());


        data.put(
                "name",
                user.getName());


        data.put(
                "email",
                user.getEmail());


        data.put(
                "status",
                user.getAccountStatus()==null
                ?
                "ACTIVE"
                :
                user.getAccountStatus());



        data.put(
                "createdAt",
                user.getCreatedAt());


        data.put(
                "lastLogin",
                user.getLastLogin());


        data.put(
                "meetingActivity",
                activity);


        data.put(
                "avatar",
                avatar);



        data.put(
                "currentMeetingId",
                user.getCurrentMeetingId());



        return Map.of(
                "success",
                true,

                "user",
                data
        );

    }





    // ============================
    // BLOCK USER
    // ============================


    @PutMapping("/users/{id}/block")
    public Map<String,Object> blockUser(
            @PathVariable Long id){


        User user =
                userRepository.findById(id)
                        .orElse(null);



        if(user==null){

            return Map.of(
                    "success",
                    false,
                    "message",
                    "User not found"
            );
        }



        user.setAccountStatus(
                "BLOCKED");


        userRepository.save(user);



        return Map.of(
                "success",
                true,
                "message",
                "User blocked successfully"
        );

    }





    // ============================
    // UNBLOCK USER
    // ============================


    @PutMapping("/users/{id}/unblock")
    public Map<String,Object> unblockUser(
            @PathVariable Long id){


        User user =
                userRepository.findById(id)
                        .orElse(null);



        if(user==null){

            return Map.of(
                    "success",
                    false,
                    "message",
                    "User not found"
            );
        }



        user.setAccountStatus(
                "ACTIVE");


        userRepository.save(user);



        return Map.of(
                "success",
                true,
                "message",
                "User unblocked successfully"
        );

    }

}