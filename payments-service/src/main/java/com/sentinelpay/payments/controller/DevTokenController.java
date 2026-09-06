package com.sentinelpay.payments.controller;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.context.annotation.Profile;

import com.sentinelpay.payments.domain.User;
import com.sentinelpay.payments.repository.UserRepository;

@RestController
@RequestMapping("/dev")
@Profile({"local", "test"})
public class DevTokenController {
    private final JwtEncoder jwtEncoder;
    private final UserRepository userRepository;

    public DevTokenController(JwtEncoder jwtEncoder, UserRepository userRepository) {
        this.jwtEncoder = jwtEncoder;
        this.userRepository = userRepository;
    }

    @PostMapping("/token/{userId}")
    public String issueToken(
        @PathVariable UUID userId
    ) {
        User user = userRepository.findById(userId).orElseThrow(() -> 
                                                    new RuntimeException("User not found"));

        Instant now = Instant.now();

        JwtClaimsSet claims = JwtClaimsSet.builder().subject(user.getUserId().toString()).issuedAt(now).expiresAt(now.plus(1, ChronoUnit.HOURS)).claim("role", user.getRole()).build();

        return jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }
}   
