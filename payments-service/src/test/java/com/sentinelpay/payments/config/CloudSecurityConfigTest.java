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

    @Test
    void requiresAccessTokenPurposeAndExpectedAppClient() {
        Jwt accessToken = jwt(
            List.of("https://api.sentinelpay.example"),
            List.of("CUSTOMER"),
            "access",
            "dashboard-client"
        );
        assertFalse(CloudSecurityConfig.claimValidator("token_use", "access")
            .validate(accessToken).hasErrors());
        assertFalse(CloudSecurityConfig.claimValidator(
            "client_id", "dashboard-client"
        ).validate(accessToken).hasErrors());
        assertTrue(CloudSecurityConfig.claimValidator("token_use", "access")
            .validate(jwt(
                List.of("https://api.sentinelpay.example"),
                List.of("CUSTOMER"),
                "id",
                "dashboard-client"
            )).hasErrors());
    }

    private Jwt jwt(List<String> audience, List<String> groups) {
        return jwt(audience, groups, "access", "dashboard-client");
    }

    private Jwt jwt(List<String> audience, List<String> groups,
        String tokenUse, String clientId) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .subject("00000000-0000-0000-0000-000000000001")
            .audience(audience)
            .issuedAt(now)
            .expiresAt(now.plusSeconds(300))
            .claim("cognito:groups", groups)
            .claim("token_use", tokenUse)
            .claim("client_id", clientId)
            .build();
    }
}
