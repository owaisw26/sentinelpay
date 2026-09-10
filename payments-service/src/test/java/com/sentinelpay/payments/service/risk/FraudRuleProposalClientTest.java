package com.sentinelpay.payments.service.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.sentinelpay.payments.exception.FraudRuleConflictException;

class FraudRuleProposalClientTest {
    @Test
    void generateAuthenticatesInternalRequestAndMapsDraftResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FraudRuleProposalClient client = new FraudRuleProposalClient(
            builder, "http://fraud.internal", "internal-token"
        );
        UUID proposalId = UUID.randomUUID();
        server.expect(requestTo("http://fraud.internal/internal/rule-proposals"))
            .andExpect(request -> assertThat(request.getMethod())
                .isEqualTo(HttpMethod.POST))
            .andExpect(header("X-Internal-Token", "internal-token"))
            .andExpect(header("X-Analyst-Subject", "analyst-42"))
            .andRespond(withSuccess("""
                {
                  "proposalId":"%s",
                  "status":"DRAFT",
                  "version":0,
                  "promptVersion":"rule-proposal-prompt-v1",
                  "schemaVersion":"rule-proposal-schema-v1",
                  "model":"contract-stub-v1",
                  "providerResponseId":"stub-response-v1",
                  "responseSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "inputSummary":{},
                  "candidate":{"rules":[]},
                  "impact":{},
                  "usage":{"inputTokens":0,"outputTokens":0,"totalTokens":0},
                  "validationFailures":[],
                  "generatedBy":"analyst-42",
                  "generatedAt":"2026-09-09T12:00:00Z",
                  "reviewedBy":null,
                  "reviewedAt":null,
                  "reviewReason":null,
                  "approvedRulesetVersion":null
                }
                """.formatted(proposalId), MediaType.APPLICATION_JSON));

        var response = client.generate("analyst-42");

        assertThat(response.proposalId()).isEqualTo(proposalId);
        assertThat(response.status()).isEqualTo("DRAFT");
        server.verify();
    }

    @Test
    void staleApprovalIsMappedToDomainConflict() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FraudRuleProposalClient client = new FraudRuleProposalClient(
            builder, "http://fraud.internal", "internal-token"
        );
        UUID proposalId = UUID.randomUUID();
        server.expect(requestTo(
                "http://fraud.internal/internal/rule-proposals/"
                    + proposalId + "/approve"
            ))
            .andExpect(jsonPath("$.expectedVersion").value(0))
            .andExpect(jsonPath("$.reason").value("Reviewed impact"))
            .andRespond(withStatus(HttpStatus.CONFLICT));

        assertThatThrownBy(() -> client.approve(
            proposalId, 0, "Reviewed impact", "analyst-42"
        )).isInstanceOf(FraudRuleConflictException.class);
        server.verify();
    }
}
