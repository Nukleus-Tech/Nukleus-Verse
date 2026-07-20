package com.nukleus.vrmeeting.repository;

import com.nukleus.vrmeeting.model.Meeting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface MeetingRepository extends JpaRepository<Meeting, Long> {

    Meeting findByRoomCodeAndStatus(String roomCode, String status);

    Meeting findByMeetingId(String meetingId);

    List<Meeting> findTop5ByOrderByCreatedAtDesc();

    List<Meeting> findByHostEmailIgnoreCase(String hostEmail);

    Meeting findByHostEmailIgnoreCaseAndStatus(
            String hostEmail,
            String status
    );

    @Query(value = """
            SELECT COUNT(*)
            FROM meetings
            WHERE DATE(created_at) = CURRENT_DATE
            """, nativeQuery = true)
    long countTodayMeetings();
}