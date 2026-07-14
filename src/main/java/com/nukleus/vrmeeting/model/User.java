package com.nukleus.vrmeeting.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @Column(unique = true)
    private String email;

    private String password;

    @Column(length = 2000)
    private String imageUrl;

    @Column(length = 2000)
    private String avatarUrl;

    @Column(length = 2000)
    private String riggedGlbUrl;

    @Column(length = 2000)
    private String walkingGlbUrl;

    @Column(length = 2000)
    private String idleGlbUrl;

    private String avatarStatus;

    private String meshyTaskId;

    // Current active meeting
    @Column(length = 100)
    private String currentMeetingId;

    // Admin Users Module Fields

    @Column(length = 20)
    private String accountStatus;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "last_login")
    private LocalDateTime lastLogin;

    @Column(unique = true)
    private String googleId;

    public User() {
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public String getRiggedGlbUrl() {
        return riggedGlbUrl;
    }

    public void setRiggedGlbUrl(String riggedGlbUrl) {
        this.riggedGlbUrl = riggedGlbUrl;
    }

    public String getWalkingGlbUrl() {
        return walkingGlbUrl;
    }

    public void setWalkingGlbUrl(String walkingGlbUrl) {
        this.walkingGlbUrl = walkingGlbUrl;
    }

    public String getIdleGlbUrl() {
        return idleGlbUrl;
    }

    public void setIdleGlbUrl(String idleGlbUrl) {
        this.idleGlbUrl = idleGlbUrl;
    }

    public String getAvatarStatus() {
        return avatarStatus;
    }

    public void setAvatarStatus(String avatarStatus) {
        this.avatarStatus = avatarStatus;
    }

    public String getMeshyTaskId() {
        return meshyTaskId;
    }

    public void setMeshyTaskId(String meshyTaskId) {
        this.meshyTaskId = meshyTaskId;
    }

    public String getCurrentMeetingId() {
        return currentMeetingId;
    }

    public void setCurrentMeetingId(String currentMeetingId) {
        this.currentMeetingId = currentMeetingId;
    }

    // Role

    // Account Status

    public String getAccountStatus() {
        return accountStatus;
    }

    public void setAccountStatus(String accountStatus) {
        this.accountStatus = accountStatus;
    }

    // Created At

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    // Last Login

    public LocalDateTime getLastLogin() {
        return lastLogin;
    }

    public void setLastLogin(LocalDateTime lastLogin) {
        this.lastLogin = lastLogin;
    }

    public String getGoogleId() {
        return googleId;
    }

    public void setGoogleId(String googleId) {
        this.googleId = googleId;
    }
}