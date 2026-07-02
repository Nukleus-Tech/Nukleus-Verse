package com.nukleus.vrmeeting.repository;

import com.nukleus.vrmeeting.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface UserRepository extends JpaRepository<User, Long> {

    User findByEmail(String email);

    User findByEmailIgnoreCase(String email);

    List<User> findByCurrentMeetingId(String currentMeetingId);
}