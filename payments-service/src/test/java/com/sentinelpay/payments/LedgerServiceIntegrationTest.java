package com.sentinelpay.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.sentinelpay.payments.domain.LedgerEntry;
import com.sentinelpay.payments.domain.LedgerTransaction;
import com.sentinelpay.payments.domain.User;
import com.sentinelpay.payments.domain.Wallet;
import com.sentinelpay.payments.repository.LedgerEntryRepository;
import com.sentinelpay.payments.repository.LedgerTransactionRepository;
import com.sentinelpay.payments.repository.WalletRepository;
import com.sentinelpay.payments.service.LedgerService;
import com.sentinelpay.payments.service.UserService;
import com.sentinelpay.payments.service.WalletService;

import jakarta.transaction.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LedgerServiceIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private WalletService walletService;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private LedgerTransactionRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository entryRepository;

    @Test
    void internalTransferIsAtomicAndDoubleEntry() {
        User senderUser = userService.createCustomer("Ledger Sender");
        User receiverUser = userService.createCustomer("Ledger Receiver");
        Wallet sender = walletService.createWallet(senderUser.getUserId(), "AUD");
        Wallet receiver = walletService.createWallet(receiverUser.getUserId(), "AUD");
        sender.setBalance(new BigDecimal("100.00"));
        walletRepository.saveAndFlush(sender);
        String reference = "internal-ledger-" + UUID.randomUUID();

        ledgerService.transfer(
            senderUser.getUserId(), sender.getId(), receiver.getId(),
            new BigDecimal("25.00"), reference
        );

        LedgerTransaction transaction = transactionRepository
            .findTransactionByReference(reference).orElseThrow();
        List<LedgerEntry> entries = entryRepository
            .findAllByLedgerTransactionId(transaction.getId());
        assertEquals(2, entries.size());
        assertEquals(0, entries.stream().map(LedgerEntry::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .compareTo(BigDecimal.ZERO));
        assertEquals(0, new BigDecimal("75.00")
            .compareTo(sender.getBalance()));
        assertEquals(0, new BigDecimal("25.00")
            .compareTo(receiver.getBalance()));
    }

    @Test
    void publicDirectTransferRouteIsRemoved() throws Exception {
        User customer = userService.createCustomer("Route Customer");
        String token = mockMvc.perform(post("/dev/token/{id}", customer.getUserId()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        mockMvc.perform(post("/wallets/transfer")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isMethodNotAllowed());
    }
}
