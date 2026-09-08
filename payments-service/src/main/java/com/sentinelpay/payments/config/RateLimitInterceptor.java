package com.sentinelpay.payments.config;

import java.security.Principal;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import com.sentinelpay.payments.service.RateLimitService;
import com.sentinelpay.payments.service.RateLimited;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {
    private final RateLimitService rateLimitService;

    public RateLimitInterceptor(RateLimitService rateLimitService) {
        this.rateLimitService = rateLimitService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
        HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RateLimited rateLimited = handlerMethod.getMethodAnnotation(
            RateLimited.class
        );
        if (rateLimited == null) {
            return true;
        }

        Principal principal = request.getUserPrincipal();
        if (principal == null) {
            return true;
        }
        rateLimitService.consume(
            UUID.fromString(principal.getName()), rateLimited.value()
        );
        return true;
    }
}
