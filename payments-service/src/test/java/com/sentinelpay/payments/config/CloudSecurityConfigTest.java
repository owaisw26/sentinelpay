package com.sentinelpay.payments.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class CloudSecurityConfigTest {
    @Test
    void requiresConfiguredAudience() {
        Jwt accepted = jwt(List.of("sentinelpay-client"), List.of("CUSTOMER"));
        Jwt rejected = jwt(List.of("another-client"), List.of("CUSTOMER"));

        assertFalse(CloudSecurityConfig.audienceValidator("sentinelpay-client")
            .validate(accepted).hasErrors());
        assertTrue(CloudSecurityConfig.audienceValidator("sentinelpay-client")
            .validate(rejected).hasErrors());
    }

    @Test
    void mapsCognitoGroupsToApplicationAuthorities() {
        Jwt token = jwt(List.of("sentinelpay-client"), List.of("ANALYST"));

        var authentication = new CloudSecurityConfig()
            .cloudJwtAuthenticationConverter()
            .convert(token);

        assertTrue(authentication.getAuthorities().stream()
            .anyMatch(authority -> authority.getAuthority().equals("ANALYST")));
    }

    private Jwt jwt(List<String> audience, List<String> groups) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .subject("00000000-0000-0000-0000-000000000001")
            .audience(audience)
            .issuedAt(now)
            .expiresAt(now.plusSeconds(300))
            .claim("cognito:groups", groups)
            .build();
    }
}
