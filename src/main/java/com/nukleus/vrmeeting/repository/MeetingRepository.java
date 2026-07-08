package com.nukleus.vrmeeting.repository;

import com.nukleus.vrmeeting.model.Meeting;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MeetingRepository extends JpaRepository<Meeting, Long> {

    Meeting findByRoomCodeAndStatus(String roomCode, String status);

    Meeting findByMeetingId(String meetingId);

    List<Meeting> findTop5ByOrderByCreatedAtDesc();

}