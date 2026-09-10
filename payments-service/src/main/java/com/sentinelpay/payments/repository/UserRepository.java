package com.sentinelpay.payments.repository;

import java.util.UUID;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.sentinelpay.payments.domain.User;

public interface UserRepository extends JpaRepository<User, UUID>{
    Optional<User> findByExternalSubject(String externalSubject);
}
