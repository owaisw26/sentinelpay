package com.sentinelpay.payments.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sentinelpay.payments.controller.request.UserRequest;
import com.sentinelpay.payments.domain.User;
import com.sentinelpay.payments.service.UserService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/users")
public class UserController {
    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    public User createUserRequest(
        @Valid @RequestBody UserRequest requestBody
    ) {
        return userService.createUser(requestBody.name(), requestBody.role());
    }
}
