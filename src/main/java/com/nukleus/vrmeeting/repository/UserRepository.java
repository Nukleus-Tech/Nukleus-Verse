package com.nukleus.vrmeeting.repository;

import com.nukleus.vrmeeting.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    User findByEmailIgnoreCase(String email);

}