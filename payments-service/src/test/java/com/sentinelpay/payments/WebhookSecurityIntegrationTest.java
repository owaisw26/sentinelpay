package com.sentinelpay.payments;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class WebhookSecurityIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void forgedWebhookIsRejectedWithoutApplicationJwt() throws Exception {
        mockMvc.perform(
            post("/webhooks/psp")
                .header(
                    "X-PSP-Timestamp",
                    Long.toString(Instant.now().getEpochSecond())
                )
                .header("X-PSP-Signature", "v1=00")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "eventId":"00000000-0000-0000-0000-000000000001",
                      "providerPaymentId":"forged",
                      "status":"SUCCEEDED"
                    }
                    """)
        )
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("INVALID_WEBHOOK_SIGNATURE"));
    }
}
