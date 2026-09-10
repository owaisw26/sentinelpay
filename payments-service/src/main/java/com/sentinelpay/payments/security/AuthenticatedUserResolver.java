package com.sentinelpay.payments.security;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import com.sentinelpay.payments.domain.User;
import com.sentinelpay.payments.exception.UserNotFoundException;
import com.sentinelpay.payments.repository.UserRepository;

@Component
public class AuthenticatedUserResolver {
    private final UserRepository userRepository;

    public AuthenticatedUserResolver(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User resolve(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new UserNotFoundException();
        }
        return userRepository.findByExternalSubject(authentication.getName())
            .orElseThrow(UserNotFoundException::new);
    }
}
