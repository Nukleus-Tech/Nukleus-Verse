package com.nukleus.vrmeeting.controller;

import com.nukleus.vrmeeting.model.Meeting;
import com.nukleus.vrmeeting.repository.MeetingRepository;
import com.nukleus.vrmeeting.repository.UserRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.Comparator;
import java.util.stream.Collectors;


@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/admin")
public class AdminDashboardController {


    @Autowired
    private UserRepository userRepository;


    @Autowired
    private MeetingRepository meetingRepository;



    // ==============================
    // DASHBOARD
    // ==============================

    @GetMapping("/dashboard")
    public Map<String,Object> getDashboard() {


        var users =
                userRepository.findAll();


        var meetings =
                meetingRepository.findAll();



        long totalUsers =
                users.size();



        long activeUsers =
                users.stream()
                        .filter(u ->
                                !"BLOCKED"
                                .equalsIgnoreCase(
                                        u.getAccountStatus()))
                        .count();



        long totalMeetings =
                meetings.size();



        long totalRecordings =
                meetings.stream()
                        .filter(m ->
                                m.getRecordingUrl()!=null
                                &&
                                !m.getRecordingUrl().isEmpty())
                        .count();



        long totalNotes =
                meetings.stream()
                        .filter(m ->
                                m.getNotesUrl()!=null
                                &&
                                !m.getNotesUrl().isEmpty())
                        .count();



        // Recent meetings

        List<Meeting> dashboardMeetings =
                new ArrayList<>(meetings);



        dashboardMeetings.sort(
                Comparator
                .comparing(
                        (Meeting m) ->
                                "ACTIVE"
                                .equalsIgnoreCase(
                                        m.getStatus())
                                ? 0 : 1
                )
                .thenComparing(
                        Meeting::getCreatedAt,
                        Comparator.nullsLast(
                                Comparator.reverseOrder()
                        )
                )
        );



        List<Map<String,Object>> recentMeetings =
                dashboardMeetings.stream()
                        .limit(5)
                        .map(m -> {


                            Map<String,Object> data =
                                    new HashMap<>();


                            data.put(
                                    "meetingName",
                                    m.getMeetingName()!=null
                                    ?
                                    m.getMeetingName()
                                    :
                                    "Untitled Meeting"
                            );


                            data.put(
                                    "hostEmail",
                                    m.getHostEmail()
                            );



                            String status =
                                    m.getStatus();



                            if("ACTIVE"
                                    .equalsIgnoreCase(status)) {

                                status="LIVE";

                            }
                            else if("ENDED"
                                    .equalsIgnoreCase(status)) {

                                status="COMPLETED";
                            }



                            data.put(
                                    "status",
                                    status
                            );


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

                        "totalMeetings",
                        totalMeetings,

                        "recordings",
                        totalRecordings,

                        "notes",
                        totalNotes

                ),


                "activities",
                List.of(
                        "New user joined meeting room",
                        "Recording generated successfully",
                        "PDF summary created for meeting"
                ),


                "recentMeetings",
                recentMeetings

        );

    }

}