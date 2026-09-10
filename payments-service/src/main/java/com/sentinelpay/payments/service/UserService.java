package com.sentinelpay.payments.service;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.sentinelpay.payments.domain.User;
import com.sentinelpay.payments.repository.UserRepository;

@Service
public class UserService {
    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User createUser(String name, String role) {
        UUID userId = UUID.randomUUID();

        User user = new User(userId, name, role, LocalDateTime.now());
        return userRepository.save(user);
    }

    public User createCustomer(String name) {
        return createUser(name, "CUSTOMER");
    }

    public User provisionCustomer(String verifiedSubject, String name) {
        return userRepository.findByExternalSubject(verifiedSubject).orElseGet(() ->
            userRepository.save(new User(
                UUID.randomUUID(),
                verifiedSubject,
                name,
                "CUSTOMER",
                LocalDateTime.now()
            ))
        );
    }

    public User provisionCustomer(UUID verifiedSubject, String name) {
        return provisionCustomer(verifiedSubject.toString(), name);
    }
}
