package com.nukleus.vrmeeting.controller;


import com.nukleus.vrmeeting.model.Meeting;
import com.nukleus.vrmeeting.repository.MeetingRepository;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;


import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;



@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/admin")
public class AdminNotesController {



    @Autowired
    private MeetingRepository meetingRepository;



    // ==============================
    // GET ALL NOTES
    // ==============================


    @GetMapping("/notes")
    public Map<String,Object> getAllNotes() {


        List<Meeting> meetings =
                meetingRepository.findAll();



        long totalNotes =
                meetings.stream()
                        .filter(m ->
                                m.getNotesUrl()!=null
                                &&
                                !m.getNotesUrl().isEmpty())
                        .count();



        LocalDate today =
                LocalDate.now();



        long todayNotes =
                meetings.stream()
                        .filter(m ->
                                m.getNotesUrl()!=null
                                &&
                                !m.getNotesUrl().isEmpty()
                                &&
                                m.getCreatedAt()!=null
                                &&
                                m.getCreatedAt()
                                .toLocalDate()
                                .equals(today))
                        .count();



        long hosts =
                meetings.stream()
                        .filter(m ->
                                m.getNotesUrl()!=null
                                &&
                                !m.getNotesUrl().isEmpty())
                        .map(Meeting::getHostEmail)
                        .filter(Objects::nonNull)
                        .distinct()
                        .count();




        List<Map<String,Object>> notes =
                meetings.stream()
                .filter(m ->
                        m.getNotesUrl()!=null
                        &&
                        !m.getNotesUrl().isEmpty())
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


                    data.put(
                            "notesUrl",
                            m.getNotesUrl()
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
                            "notesPreview",
                            "Available Soon"
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


                "cards",
                Map.of(

                        "totalNotes",
                        totalNotes,

                        "today",
                        todayNotes,

                        "hosts",
                        hosts

                ),


                "notes",
                notes

        );

    }





    // ==============================
    // GET NOTE DETAILS
    // ==============================


    @GetMapping("/notes/{meetingId}")
    public Map<String,Object> getNoteDetails(
            @PathVariable String meetingId) {


        Meeting meeting =
                meetingRepository
                        .findByMeetingId(meetingId);



        if(meeting==null) {

            return Map.of(
                    "success",
                    false,
                    "message",
                    "Meeting not found"
            );
        }



        if(meeting.getNotesUrl()==null
                ||
           meeting.getNotesUrl().isEmpty()) {


            return Map.of(
                    "success",
                    false,
                    "message",
                    "Notes not available"
            );

        }



        Map<String,Object> notes =
                new HashMap<>();


        notes.put(
                "meetingName",
                meeting.getMeetingName()!=null
                ?
                meeting.getMeetingName()
                :
                "Untitled Meeting"
        );


        notes.put(
                "hostEmail",
                meeting.getHostEmail()
        );


        notes.put(
                "created",
                meeting.getCreatedAt()
        );


        notes.put(
                "notesPreview",
                "Available Soon"
        );


        notes.put(
                "notesUrl",
                meeting.getNotesUrl()
        );


        notes.put(
                "meetingId",
                meeting.getMeetingId()
        );



        return Map.of(

                "success",
                true,

                "notes",
                notes
        );

    }


}