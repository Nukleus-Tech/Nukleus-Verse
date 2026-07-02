package com.nukleus.vrmeeting.repository;

import com.nukleus.vrmeeting.model.EmailOtp;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface EmailOtpRepository extends JpaRepository<EmailOtp, Long> {

    EmailOtp findTopByEmailOrderByIdDesc(String email);

    @Transactional
    void deleteByEmail(String email);
}