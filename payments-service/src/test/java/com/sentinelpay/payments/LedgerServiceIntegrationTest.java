package com.sentinelpay.payments;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sentinelpay.payments.domain.LedgerEntry;
import com.sentinelpay.payments.domain.LedgerTransaction;
import com.sentinelpay.payments.domain.User;
import com.sentinelpay.payments.domain.Wallet;
import com.sentinelpay.payments.repository.LedgerEntryRepository;
import com.sentinelpay.payments.repository.LedgerTransactionRepository;
import com.sentinelpay.payments.repository.WalletRepository;
import com.sentinelpay.payments.service.UserService;
import com.sentinelpay.payments.service.WalletService;

import jakarta.transaction.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
public class LedgerServiceIntegrationTest extends AbstractIntegrationTest {
      @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private WalletService walletService;

    @Autowired
    private LedgerTransactionRepository ledgerTransactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private WalletRepository walletRepository;

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
    public void testTransaction() throws Exception {
        // create a user, and 2 wallets, move money between them
        User userOne = userService.createUser("TesterOne", "CUSTOMER");
        User userTwo = userService.createUser("TesterTwo", "CUSTOMER");

        Wallet walletOne = walletService.createWallet(userOne.getUserId(), "AUD");

        Wallet walletTwo = walletService.createWallet(userTwo.getUserId(), "AUD");

        walletOne.setBalance(new BigDecimal(200));

        String token = issueToken(userOne.getUserId());

        // create the transaction and verify the balance is updated
        mockMvc.perform(
            post("/wallets/transfer").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(
                """
                {
                    "senderWallet": "%s",
                    "receiverWallet": "%s",
                    "reference": "%s",
                    "amount": "%s"
                }
                        """
            .formatted(walletOne.getId(), walletTwo.getId(), "1-2-3-4", 15))
        ).andExpect(status().isOk())
        .andExpect(jsonPath("$.walletId").value(walletOne.getId().toString())).andExpect(jsonPath("$.balance").value("185"));
    }

    @Test
    public void transactionInsufficientFunds() throws Exception {
        User user = userService.createUser("Tester", "CUSTOMER");

        Wallet walletOne = walletService.createWallet(user.getUserId(), "AUD");

        Wallet walletTwo = walletService.createWallet(user.getUserId(), "AUZ");

        walletOne.setBalance(new BigDecimal(1));

        String token = issueToken(user.getUserId());

        mockMvc.perform(
            post("/wallets/transfer").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(
                """
                {
                    "senderWallet": "%s",
                    "receiverWallet": "%s",
                    "reference": "%s",
                    "amount": "%s"
                }
                        """
            .formatted(walletOne.getId(), walletTwo.getId(), "1-2-3-4", 15))
        ).andExpect(status().isUnprocessableContent());
    }

    @Test
    public void customerCannotTransferFromAnotherUsersWallet() throws Exception {
        User one = userService.createUser("TestOne", "CUSTOMER");
        User two = userService.createUser("TestTwo", "CUSTOMER");

        Wallet walletOne = walletService.createWallet(one.getUserId(), "AUD");
        Wallet walletTwo = walletService.createWallet(two.getUserId(), "AUD");

        String token = issueToken(one.getUserId());
        walletOne.setBalance(new BigDecimal(100));
        // one attempts to create a transfer with walletTwo
        mockMvc.perform(
            post("/wallets/transfer").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(
                """ 
                {
                    "senderWallet": "%s",
                    "receiverWallet": "%s",
                    "reference": "%s",
                    "amount": "%s"
                } 
                """.formatted(walletTwo.getId(), walletOne.getId(), "1-2-3-4", 15)
            )
        ).andExpect(status().isForbidden());
    }

    @Test
    public void sameWalletTransferIsRejectedWithoutChangingBalance() throws Exception {
        User one = userService.createUser("TestOne", "CUSTOMER");

        Wallet walletOne = walletService.createWallet(one.getUserId(), "AUD");

        String token = issueToken(one.getUserId());
        walletOne.setBalance(new BigDecimal(100));
        // one attempts to create a transfer with walletTwo
        mockMvc.perform(
            post("/wallets/transfer").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(
                """ 
                {
                    "senderWallet": "%s",
                    "receiverWallet": "%s",
                    "reference": "%s",
                    "amount": "%s"
                } 
                """.formatted(walletOne.getId(), walletOne.getId(), "1-2-3-4", 15)
            )
        ).andExpect(status().isUnprocessableContent());
    }

    @Test
    public void crossCurrencyTransferIsRejectedWithoutChangingBalances() throws Exception {
        User one = userService.createUser("TestOne", "CUSTOMER");

        Wallet walletOne = walletService.createWallet(one.getUserId(), "AUD");
        Wallet walletTwo = walletService.createWallet(one.getUserId(), "USD");

        String token = issueToken(one.getUserId());

        mockMvc.perform(
            post("/wallets/transfer").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content("""
                    {
                        "senderWallet": "%s",
                        "receiverWallet": "%s",
                        "reference": "%s",
                        "amount": "%s"
                    }
                    """.formatted(walletOne.getId(), walletTwo.getId(), "1-2-3-4", 15))
        ).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.error")
              .value("DIFFERENT_WALLET_CURRENCY_NOT_ALLOWED"))
          .andExpect(jsonPath("$.message")
              .value("Wallets must have same currency"));
    }

    @Test
    public void ledgerEntrySumIsZero() throws Exception {
        User one = userService.createUser("TestOne", "CUSTOMER");
        User two = userService.createUser("TestTwo", "CUSTOMER");

        Wallet walletOne = walletService.createWallet(one.getUserId(), "AUD");
        Wallet walletTwo = walletService.createWallet(two.getUserId(), "AUD");

        walletOne.setBalance(new BigDecimal("200.00"));
        walletRepository.saveAndFlush(walletOne);

        String token = issueToken(one.getUserId());
        String reference = "ledger-test-" + UUID.randomUUID();

        mockMvc.perform(
            post("/wallets/transfer").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content("""
                    {
                        "senderWallet": "%s",
                        "receiverWallet": "%s",
                        "reference": "%s",
                        "amount": 15.00
                    }
                    """.formatted(walletOne.getId(), walletTwo.getId(), reference))
        ).andExpect(status().isOk());

        LedgerTransaction transaction = ledgerTransactionRepository
            .findTransactionByReference(reference)
            .orElseThrow();

        UUID transactionId = transaction.getId();

        // now find the entries with this transaction_id
        List<LedgerEntry> entries = ledgerEntryRepository.findAllByLedgerTransactionId(transactionId);

        assertEquals(2, entries.size());

        BigDecimal total = entries.stream()
            .map(LedgerEntry::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertEquals(0, total.compareTo(BigDecimal.ZERO));

        assertEquals(1, entries.stream()
            .filter(entry -> entry.getWallet().getId().equals(walletOne.getId()))
            .filter(entry -> entry.getAmount().compareTo(new BigDecimal("-15.00")) == 0)
            .count());

        assertEquals(1, entries.stream()
            .filter(entry -> entry.getWallet().getId().equals(walletTwo.getId()))
            .filter(entry -> entry.getAmount().compareTo(new BigDecimal("15.00")) == 0)
            .count());
    }
}
