package com.sentinelpay.payments.controller;

import java.util.Comparator;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sentinelpay.payments.controller.response.DevPersonaResponse;
import com.sentinelpay.payments.repository.UserRepository;

@RestController
@RequestMapping("/dev/personas")
@Profile("local")
public class DevPersonaController {
    private final UserRepository userRepository;

    public DevPersonaController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping
    public List<DevPersonaResponse> list() {
        return userRepository.findAll().stream()
            .sorted(Comparator.comparing(user -> user.getRole() + user.getName()))
            .map(DevPersonaResponse::from)
            .toList();
    }
}
