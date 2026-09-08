package com.sentinelpay.payments;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;
import com.sentinelpay.payments.domain.User;
import com.sentinelpay.payments.domain.Wallet;
import com.sentinelpay.payments.service.PayeeCheckService;
import com.sentinelpay.payments.service.UserService;
import com.sentinelpay.payments.service.WalletService;

import jakarta.transaction.Transactional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PayeeVerificationIntegrationTest extends AbstractIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private UserService userService;
    @Autowired private WalletService walletService;
    @Autowired private PayeeCheckService payeeCheckService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @PersistenceContext private EntityManager entityManager;

    @Test
    void checkResponseAndPersistenceDoNotRevealEitherName() throws Exception {
        Fixture fixture = fixture("Private Legal Beneficiary");
        String supplied = "Completely Different Person";

        MvcResult result = createCheck(fixture, supplied)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.checkId").isNotEmpty())
            .andExpect(jsonPath("$.outcome").value("NO_MATCH"))
            .andExpect(jsonPath("$.reasonCode").value("NAME_NOT_MATCHED"))
            .andExpect(jsonPath("$.expiresAt").isNotEmpty())
            .andExpect(jsonPath("$.legalName").doesNotExist())
            .andExpect(jsonPath("$.suppliedName").doesNotExist())
            .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        String response = responseBody.toLowerCase(Locale.ROOT);
        assertFalse(response.contains("private legal beneficiary"));
        assertFalse(response.contains("completely different person"));

        UUID checkId = UUID.fromString(JsonPath.read(responseBody, "$.checkId"));
        String storedHash = jdbcTemplate.queryForObject(
            "select supplied_name_hash from payee_checks where id = ?",
            String.class, checkId
        );
        assertNotEquals(supplied, storedHash);
        assertTrue(storedHash.matches("[0-9a-f]{64}"));
    }

    @Test
    void exactCheckCanCreatePaymentAndBeReusedUntilExpiry() throws Exception {
        Fixture fixture = fixture("Jos\u00e9 O'Connor");
        UUID checkId = checkId(createCheck(fixture, "JOSE O\u2019CONNOR")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.outcome").value("MATCH"))
            .andReturn());

        createPayment(fixture, checkId, false, "exact-one")
            .andExpect(status().isCreated());
        createPayment(fixture, checkId, false, "exact-two")
            .andExpect(status().isCreated());
    }

    @Test
    void mismatchRequiresAcceptanceAndIsConsumedOnce() throws Exception {
        Fixture fixture = fixture("John Smith");
        UUID checkId = checkId(createCheck(fixture, "Different Person")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.outcome").value("NO_MATCH"))
            .andReturn());

        createPayment(fixture, checkId, false, "not-accepted")
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.errorCode")
                .value("PAYEE_NAME_MISMATCH_NOT_ACCEPTED"));

        createPayment(fixture, checkId, true, "accepted")
            .andExpect(status().isCreated());

        createPayment(fixture, checkId, true, "accepted")
            .andExpect(status().isCreated())
            .andExpect(header().string("Idempotency-Replayed", "true"));

        createPayment(fixture, checkId, true, "reuse-attempt")
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.errorCode").value("PAYEE_CHECK_INVALID"));
    }

    @Test
    void expiredCheckAndCheckOwnedByAnotherCustomerAreRejected()
        throws Exception {
        Fixture fixture = fixture("Expiry Target");
        UUID expiredCheckId = checkId(createCheck(fixture, "Expiry Target")
            .andExpect(status().isCreated()).andReturn());
        jdbcTemplate.update(
            "update payee_checks set created_at = now() - interval '2 hours', "
                + "expires_at = now() - interval '1 hour' where id = ?",
            expiredCheckId
        );
        entityManager.clear();

        createPayment(fixture, expiredCheckId, false, "expired")
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.errorCode").value("PAYEE_CHECK_INVALID"));

        User otherUser = userService.createCustomer("Other Customer");
        Wallet otherSender = walletService.createWallet(
            otherUser.getUserId(), "AUD"
        );
        Fixture other = new Fixture(
            otherUser, otherSender, fixture.receiver(),
            issueToken(otherUser.getUserId())
        );
        UUID ownedCheckId = checkId(createCheck(fixture, "Expiry Target")
            .andExpect(status().isCreated()).andReturn());

        createPayment(other, ownedCheckId, false, "wrong-owner")
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.errorCode").value("PAYEE_CHECK_INVALID"));
    }

    @Test
    void registryVersionChangeInvalidatesEarlierChecks() throws Exception {
        Fixture fixture = fixture("Original Registered Name");
        UUID staleCheckId = checkId(createCheck(
            fixture, "Original Registered Name"
        ).andExpect(status().isCreated()).andReturn());

        int oldVersion = jdbcTemplate.queryForObject(
            "select registry_version from payee_checks where id = ?",
            Integer.class, staleCheckId
        );
        payeeCheckService.replaceRegistryName(
            fixture.receiver().getId(), "Updated Registered Name"
        );

        createPayment(fixture, staleCheckId, false, "stale-version")
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.errorCode").value("PAYEE_CHECK_INVALID"));

        MvcResult current = createCheck(fixture, "Updated Registered Name")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.outcome").value("MATCH"))
            .andReturn();
        int newVersion = jdbcTemplate.queryForObject(
            "select registry_version from payee_checks where id = ?",
            Integer.class, checkId(current)
        );
        assertTrue(newVersion > oldVersion);
    }

    private Fixture fixture(String receiverLegalName) throws Exception {
        User senderUser = userService.createCustomer("Payee Check Sender");
        User receiverUser = userService.createCustomer(receiverLegalName);
        Wallet sender = walletService.createWallet(senderUser.getUserId(), "AUD");
        Wallet receiver = walletService.createWallet(
            receiverUser.getUserId(), "AUD"
        );
        return new Fixture(
            senderUser, sender, receiver, issueToken(senderUser.getUserId())
        );
    }

    private String issueToken(UUID userId) throws Exception {
        return mockMvc.perform(post("/dev/token/{userId}", userId))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    }

    private org.springframework.test.web.servlet.ResultActions createCheck(
        Fixture fixture, String suppliedName
    ) throws Exception {
        return mockMvc.perform(post("/payee-checks")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.token())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"receiverWalletId":"%s","suppliedName":"%s"}
                """.formatted(fixture.receiver().getId(), suppliedName)));
    }

    private org.springframework.test.web.servlet.ResultActions createPayment(
        Fixture fixture, UUID checkId, boolean acceptMismatch, String key
    ) throws Exception {
        return mockMvc.perform(post("/payments")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.token())
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "senderWalletId":"%s",
                  "receiverWalletId":"%s",
                  "amount":10.00,
                  "currency":"AUD",
                  "reference":"payee verification",
                  "payeeCheckId":"%s",
                  "acceptNameMismatch":%s
                }
                """.formatted(
                    fixture.sender().getId(), fixture.receiver().getId(),
                    checkId, acceptMismatch
                )));
    }

    private UUID checkId(MvcResult result) throws Exception {
        return UUID.fromString(JsonPath.read(
            result.getResponse().getContentAsString(), "$.checkId"
        ));
    }

    private record Fixture(
        User senderUser,
        Wallet sender,
        Wallet receiver,
        String token
    ) {}
}
