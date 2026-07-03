package com.nukleus.vrmeeting.repository;

import com.nukleus.vrmeeting.model.Admin;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminRepository extends JpaRepository<Admin, Long> {
    Admin findByEmailIgnoreCase(String email);
}