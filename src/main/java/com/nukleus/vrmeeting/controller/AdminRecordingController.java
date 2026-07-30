package com.nukleus.vrmeeting.controller;

import com.nukleus.vrmeeting.model.Meeting;
import com.nukleus.vrmeeting.repository.MeetingRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;


@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/admin")
public class AdminRecordingController {


    @Autowired
    private MeetingRepository meetingRepository;



    // ==============================
    // GET ALL RECORDINGS
    // ==============================

    @GetMapping("/recordings")
    public Map<String,Object> getAllRecordings() {


        List<Map<String,Object>> recordings =
                meetingRepository.findAll()
                .stream()
                .filter(m ->
                        m.getRecordingUrl()!=null
                        &&
                        !m.getRecordingUrl().isEmpty())
                .map(m -> {


                    Map<String,Object> data =
                            new HashMap<>();


                    data.put(
                            "recordingName",
                            m.getRecordingFileName()!=null
                            ?
                            m.getRecordingFileName()
                            :
                            "Recording File"
                    );


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


                    String duration =
                            "Not Available";


                    if(m.getCreatedAt()!=null
                    &&
                    m.getEndedAt()!=null) {


                        long seconds =
                                Duration.between(
                                        m.getCreatedAt(),
                                        m.getEndedAt())
                                .getSeconds();


                        long minutes =
                                seconds / 60;


                        if(minutes <= 0) {

                            duration =
                                    "Less than 1 min";

                        }
                        else if(minutes < 60) {

                            duration =
                                    minutes + " min";

                        }
                        else {

                            long hours =
                                    minutes / 60;

                            long remainingMinutes =
                                    minutes % 60;


                            if(remainingMinutes==0) {

                                duration =
                                        hours +
                                        (hours==1
                                        ? " hour"
                                        : " hours");

                            }
                            else {

                                duration =
                                        hours +
                                        (hours==1
                                        ? " hour "
                                        : " hours ")
                                        +
                                        remainingMinutes
                                        +
                                        " min";
                            }
                        }
                    }


                    data.put(
                            "duration",
                            duration
                    );


                    data.put(
                            "fileSize",
                            m.getRecordingFileSize()!=null
                            ?
                            m.getRecordingFileSize()
                            :
                            "Not Available"
                    );


                    data.put(
                            "created",
                            m.getCreatedAt()!=null
                            ?
                            m.getCreatedAt()
                            :
                            "Not Available"
                    );


                    data.put(
                            "status",
                            "AVAILABLE"
                    );


                    data.put(
                            "recordingUrl",
                            m.getRecordingUrl()
                    );


                    data.put(
                            "meetingId",
                            m.getMeetingId()
                    );


                    return data;


                })
                .collect(Collectors.toList());



        return Map.of(
                "success",
                true,

                "recordings",
                recordings
        );

    }

}