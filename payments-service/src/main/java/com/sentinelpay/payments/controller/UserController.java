package com.sentinelpay.payments.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.AnonymousAuthenticationToken;

import com.sentinelpay.payments.controller.request.UserRequest;
import com.sentinelpay.payments.controller.response.UserResponse;
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
    public UserResponse createUserRequest(
        @Valid @RequestBody UserRequest requestBody,
        Authentication authentication
    ) {
        User user = authentication == null ||
            authentication instanceof AnonymousAuthenticationToken
            ? userService.createCustomer(requestBody.name())
            : userService.provisionCustomer(
                authentication.getName(),
                requestBody.name()
            );
        return UserResponse.from(user);
    }
}
