package com.sentinelpay.payments;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sentinelpay.payments.domain.User;
import com.sentinelpay.payments.domain.Wallet;
import com.sentinelpay.payments.service.UserService;
import com.sentinelpay.payments.service.WalletService;

import jakarta.transaction.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
public class WalletSecurityIntegrationTest extends AbstractIntegrationTest {
    
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private WalletService walletService;

    private String issueToken(UUID userId) throws Exception {
        return mockMvc.perform(
                post("/dev/token/{userId}", userId)
            )
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    }

    @Test
    void unauthenticatedUserCannotCreateWallet() throws Exception {
        mockMvc.perform(
            post("/wallets").
            contentType(MediaType.APPLICATION_JSON).
            content("""
                {
                    "currency": "AUD"
                }
                    """)
        ).andExpect(status().isUnauthorized());
    }

    @Test
    void customerCreatesWalletForThemself() throws Exception {
        User customer = userService.createUser("Tester", "CUSTOMER");
        String token = issueToken(customer.getUserId());

        mockMvc.perform(
            post("/wallets").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).
            contentType(MediaType.APPLICATION_JSON).content(
                """
                    {
                        "currency": "AUD"
                    }
                """
            )
        ).andExpect(status().is2xxSuccessful()).
            andExpect(jsonPath("$.userId").value(customer.getUserId().toString())
            ).
            andExpect(jsonPath("$.currency").value("AUD")).
            andExpect(jsonPath("$.balance").value(0)).
            andExpect(jsonPath("$.reservedBalance").value(0)).
            andExpect(jsonPath("$.availableBalance").value(0));
    }

    @Test
    void customerCanRetrieveOwnWallet() throws Exception {
        User customer = userService.createUser("Tester", "CUSTOMER");
        String token = issueToken(customer.getUserId());

        Wallet wallet = walletService.createWallet(customer.getUserId(), "AUD");

        mockMvc.perform(
            get("/wallets/{walletId}", wallet.getId()).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        ).andExpect(status().isOk()).andExpect(
            jsonPath("$.walletId").value(wallet.getId().toString())
        ).andExpect(jsonPath("$.userId").value(customer.getUserId().toString()));
    }

    @Test
    void customerCannotAccessAnotherWallet() throws Exception {
        User customerOne = userService.createUser("TesterOne", "CUSTOMER");
        User customerTwo = userService.createUser("TesterTwo", "CUSTOMER");

        // wallet Two belongs to customer two, but customer one will attempt to
        // access
        Wallet walletTwo = walletService.createWallet(customerTwo.getUserId(), "AUD");

        // customerOne attempts to access walletTwo
        String tokenOne = issueToken(customerOne.getUserId());

        mockMvc.perform(
            get("/wallets/{walletId}", walletTwo.getId()).header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenOne)
        ).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Wallet not found"));
    }

    @Test
    void customerCannotAccessAnalystEndpoint() throws Exception {
        User customerOne = userService.createUser("TesterOne", "CUSTOMER");
        String token = issueToken(customerOne.getUserId());

        mockMvc.perform(
            get("/analyst/test").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        ).andExpect(status().isForbidden());
    }

    @Test
    void customerCanAccessAnalystEndpoint() throws Exception {
        User customerOne = userService.createUser("TesterOne", "ANALYST");
        String token = issueToken(customerOne.getUserId());

        mockMvc.perform(
            get("/analyst/test").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        ).andExpect(status().isOk());
    }

    @Test
    void publicRegistrationCannotSelectAnalystRole() throws Exception {
        mockMvc.perform(post("/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "name":"Attempted Analyst",
                      "role":"ANALYST"
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"));
    }

    @Test
    void publicRegistrationAlwaysCreatesCustomerWithoutExposingRole()
        throws Exception {
        mockMvc.perform(post("/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"New Customer"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").isNotEmpty())
            .andExpect(jsonPath("$.name").value("New Customer"))
            .andExpect(jsonPath("$.role").doesNotExist());
    }
}
