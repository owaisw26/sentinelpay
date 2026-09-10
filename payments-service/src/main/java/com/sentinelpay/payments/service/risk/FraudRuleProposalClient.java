package com.sentinelpay.payments.service.risk;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.sentinelpay.payments.controller.response.RuleProposalResponse;
import com.sentinelpay.payments.controller.response.RuleSetResponse;
import com.sentinelpay.payments.exception.FraudRuleConflictException;
import com.sentinelpay.payments.exception.FraudRuleNotFoundException;
import com.sentinelpay.payments.exception.FraudRuleServiceUnavailableException;

@Component
public class FraudRuleProposalClient implements FraudRuleProposalGateway {
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
    private static final String ANALYST_SUBJECT_HEADER = "X-Analyst-Subject";

    private final RestClient restClient;
    private final String internalToken;

    @Autowired
    public FraudRuleProposalClient(
        @Value("${sentinelpay.fraud.base-url}") String baseUrl,
        @Value("${sentinelpay.fraud.internal-token}") String internalToken
    ) {
        this(RestClient.builder(), baseUrl, internalToken);
    }

    FraudRuleProposalClient(
        RestClient.Builder builder,
        String baseUrl,
        String internalToken
    ) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.internalToken = internalToken;
    }

    @Override
    public RuleProposalResponse generate(String analystSubject) {
        return call(() -> restClient.post()
            .uri("/internal/rule-proposals")
            .headers(headers -> authenticate(headers, analystSubject))
            .retrieve()
            .body(RuleProposalResponse.class));
    }

    @Override
    public RuleProposalResponse get(UUID proposalId, String analystSubject) {
        return call(() -> restClient.get()
            .uri("/internal/rule-proposals/{id}", proposalId)
            .headers(headers -> authenticate(headers, analystSubject))
            .retrieve()
            .body(RuleProposalResponse.class));
    }

    @Override
    public RuleSetResponse approve(
        UUID proposalId,
        long expectedVersion,
        String reason,
        String analystSubject
    ) {
        return call(() -> restClient.post()
            .uri("/internal/rule-proposals/{id}/approve", proposalId)
            .headers(headers -> authenticate(headers, analystSubject))
            .body(Map.of("expectedVersion", expectedVersion, "reason", reason))
            .retrieve()
            .body(RuleSetResponse.class));
    }

    @Override
    public RuleProposalResponse reject(
        UUID proposalId,
        long expectedVersion,
        String reason,
        String analystSubject
    ) {
        return call(() -> restClient.post()
            .uri("/internal/rule-proposals/{id}/reject", proposalId)
            .headers(headers -> authenticate(headers, analystSubject))
            .body(Map.of("expectedVersion", expectedVersion, "reason", reason))
            .retrieve()
            .body(RuleProposalResponse.class));
    }

    @Override
    public RuleSetResponse deactivate(
        UUID ruleId,
        long expectedRulesetVersion,
        String reason,
        String analystSubject
    ) {
        return call(() -> restClient.post()
            .uri("/internal/rules/{id}/deactivate", ruleId)
            .headers(headers -> authenticate(headers, analystSubject))
            .body(Map.of(
                "expectedRulesetVersion", expectedRulesetVersion,
                "reason", reason
            ))
            .retrieve()
            .body(RuleSetResponse.class));
    }

    @Override
    public RuleSetResponse rollback(
        long targetVersion,
        long expectedRulesetVersion,
        String reason,
        String analystSubject
    ) {
        return call(() -> restClient.post()
            .uri("/internal/rulesets/{version}/rollback", targetVersion)
            .headers(headers -> authenticate(headers, analystSubject))
            .body(Map.of(
                "expectedRulesetVersion", expectedRulesetVersion,
                "reason", reason
            ))
            .retrieve()
            .body(RuleSetResponse.class));
    }

    @Override
    public RuleSetResponse active(String analystSubject) {
        return call(() -> restClient.get()
            .uri("/internal/rulesets/active")
            .headers(headers -> authenticate(headers, analystSubject))
            .retrieve()
            .body(RuleSetResponse.class));
    }

    private void authenticate(
        org.springframework.http.HttpHeaders headers,
        String analystSubject
    ) {
        headers.set(INTERNAL_TOKEN_HEADER, internalToken);
        headers.set(ANALYST_SUBJECT_HEADER, analystSubject);
    }

    private <T> T call(Supplier<T> request) {
        try {
            T response = request.get();
            if (response == null) {
                throw new IllegalStateException("Fraud service returned no body");
            }
            return response;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new FraudRuleNotFoundException("Rule resource not found");
            }
            if (exception.getStatusCode() == HttpStatus.CONFLICT) {
                throw new FraudRuleConflictException(
                    "Rule proposal or ruleset version is stale"
                );
            }
            throw new FraudRuleServiceUnavailableException(
                "Fraud rule service request failed", exception
            );
        } catch (FraudRuleNotFoundException | FraudRuleConflictException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new FraudRuleServiceUnavailableException(
                "Fraud rule service is unavailable", exception
            );
        }
    }
}
