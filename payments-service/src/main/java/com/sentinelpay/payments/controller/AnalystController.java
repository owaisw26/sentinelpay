package com.sentinelpay.payments.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/analyst")
public class AnalystController {
    public AnalystController() {}

    @GetMapping("/test")
    @PreAuthorize("hasAuthority('ANALYST')")
    public String analystTest() {
        return "analyst access granted";
    }
}
