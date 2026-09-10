package com.sentinelpay.payments.domain;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class User {
    public User(
            UUID userId,
            String name,
            String role,
            LocalDateTime createdAt
    ) {
        this(userId, userId.toString(), name, role, createdAt);
    }

    public User(
            UUID userId,
            String externalSubject,
            String name,
            String role,
            LocalDateTime createdAt
    ) {
        this.userId = userId;
        this.externalSubject = externalSubject;
        this.name = name;
        this.role = role;
        this.createdAt = createdAt;
    }

    public User() {

    }

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "external_subject", nullable = false, unique = true)
    private String externalSubject;

    @Column(name = "name")
    private String name;

    @Column(name = "role")
    private String role;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public UUID getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public String getExternalSubject() {
        return externalSubject;
    }

    public String getRole() {
        return role;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
