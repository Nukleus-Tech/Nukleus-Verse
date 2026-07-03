package com.nukleus.vrmeeting.repository;

import com.nukleus.vrmeeting.model.Meeting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MeetingRepository extends JpaRepository<Meeting, Long> {

    Meeting findByRoomCodeAndStatus(String roomCode, String status);

    Meeting findByMeetingId(String meetingId);
}