package com.sentinelpay.payments.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
@Profile("cloud")
public class CloudSecurityConfig {
    @Bean
    JwtDecoder cloudJwtDecoder(
        @Value("${sentinelpay.cloud.issuer}") String issuer,
        @Value("${sentinelpay.cloud.audience}") String audience,
        @Value("${sentinelpay.cloud.app-client-id}") String appClientId
    ) {
        NimbusJwtDecoder decoder = (NimbusJwtDecoder)
            JwtDecoders.fromIssuerLocation(issuer);
        OAuth2TokenValidator<Jwt> issuerAndTime =
            JwtValidators.createDefaultWithIssuer(issuer);
        OAuth2TokenValidator<Jwt> audienceValidator = audienceValidator(audience);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            issuerAndTime,
            audienceValidator,
            claimValidator("token_use", "access"),
            claimValidator("client_id", appClientId)
        ));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> audienceValidator(String audience) {
        return jwt ->
            jwt.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    "invalid_token",
                    "Required audience is missing",
                    null
                ));
    }

    static OAuth2TokenValidator<Jwt> claimValidator(
        String claimName,
        String expectedValue
    ) {
        return jwt -> expectedValue.equals(jwt.getClaimAsString(claimName))
            ? OAuth2TokenValidatorResult.success()
            : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                "invalid_token",
                "Required " + claimName + " claim is missing or invalid",
                null
            ));
    }

    @Bean
    Converter<Jwt, AbstractAuthenticationToken> cloudJwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities =
            new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("cognito:groups");
        authorities.setAuthorityPrefix("");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    @Bean
    SecurityFilterChain cloudSecurityFilterChain(
        HttpSecurity http,
        Converter<Jwt, AbstractAuthenticationToken> cloudJwtAuthenticationConverter
    ) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/webhooks/psp").permitAll()
                .requestMatchers("/actuator/**").hasAuthority("ANALYST")
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt ->
                jwt.jwtAuthenticationConverter(cloudJwtAuthenticationConverter)
            ));
        return http.build();
    }
}
