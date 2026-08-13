package com.tuum.banking.service;

import com.tuum.banking.exception.AccountNotFoundException;
import com.tuum.banking.exception.BalanceNotFoundException;
import com.tuum.banking.exception.InsufficientFundsException;
import com.tuum.banking.messaging.EventPublisher;
import com.tuum.banking.messaging.event.BalanceUpdatedEvent;
import com.tuum.banking.messaging.event.EventType;
import com.tuum.banking.messaging.event.TransactionCreatedEvent;
import com.tuum.banking.model.dto.CreateTransactionRequest;
import com.tuum.banking.model.dto.TransactionResponse;
import com.tuum.banking.model.entity.Balance;
import com.tuum.banking.model.entity.Transaction;
import com.tuum.banking.model.enums.Currency;
import com.tuum.banking.model.enums.Direction;
import com.tuum.banking.repository.AccountMapper;
import com.tuum.banking.repository.BalanceMapper;
import com.tuum.banking.repository.TransactionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    private static final long ACCOUNT_ID = 1L;

    @Mock
    private AccountMapper accountMapper;

    @Mock
    private BalanceMapper balanceMapper;

    @Mock
    private TransactionMapper transactionMapper;

    @Mock
    private EventPublisher eventPublisher;

    @InjectMocks
    private TransactionService transactionService;

    @BeforeEach
    void stubGeneratedTransactionId() {
        lenient().doAnswer(invocation -> {
            invocation.getArgument(0, Transaction.class).setId(500L);
            return null;
        }).when(transactionMapper).insert(any(Transaction.class));
    }

    private Balance lockedBalance(String amount) {
        Balance balance = new Balance(ACCOUNT_ID, Currency.EUR, new BigDecimal(amount));
        balance.setId(10L);
        when(accountMapper.existsById(ACCOUNT_ID)).thenReturn(true);
        when(balanceMapper.findByAccountIdAndCurrencyForUpdate(ACCOUNT_ID, Currency.EUR)).thenReturn(balance);
        return balance;
    }

    private static CreateTransactionRequest request(String amount, Direction direction) {
        return new CreateTransactionRequest(new BigDecimal(amount), Currency.EUR, direction, "salary");
    }

    @Test
    @DisplayName("IN increases the balance and reports the new balanceAfter")
    void inboundIncreasesBalance() {
        lockedBalance("100.0000");

        TransactionResponse response = transactionService.createTransaction(ACCOUNT_ID, request("25.5000", Direction.IN));

        assertThat(response.balanceAfter()).isEqualByComparingTo("125.50");
        assertThat(response.transactionId()).isEqualTo(500L);
        assertThat(response.direction()).isEqualTo(Direction.IN);
        verify(balanceMapper).updateAmount(eq(10L), argThatEquals("125.5000"));
    }

    @Test
    @DisplayName("OUT decreases the balance")
    void outboundDecreasesBalance() {
        lockedBalance("100.0000");

        TransactionResponse response = transactionService.createTransaction(ACCOUNT_ID, request("40.0000", Direction.OUT));

        assertThat(response.balanceAfter()).isEqualByComparingTo("60.00");
        verify(balanceMapper).updateAmount(eq(10L), argThatEquals("60.0000"));
    }

    @Test
    @DisplayName("OUT may empty the balance exactly to zero")
    void outboundToExactlyZeroIsAllowed() {
        lockedBalance("75.0000");

        TransactionResponse response = transactionService.createTransaction(ACCOUNT_ID, request("75.0000", Direction.OUT));

        assertThat(response.balanceAfter()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("OUT beyond the available amount is rejected and writes nothing")
    void outboundBeyondBalanceIsRejected() {
        lockedBalance("30.0000");

        assertThatThrownBy(() -> transactionService.createTransaction(ACCOUNT_ID, request("30.0001", Direction.OUT)))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessageContaining("Insufficient funds");

        verify(balanceMapper, never()).updateAmount(any(), any());
        verify(transactionMapper, never()).insert(any());
        verify(eventPublisher, never()).publishAfterCommit(any(), any());
    }

    @Test
    @DisplayName("unknown account is rejected before the balance is locked")
    void unknownAccountIsRejected() {
        when(accountMapper.existsById(ACCOUNT_ID)).thenReturn(false);

        assertThatThrownBy(() -> transactionService.createTransaction(ACCOUNT_ID, request("10.0000", Direction.IN)))
                .isInstanceOf(AccountNotFoundException.class);

        verify(balanceMapper, never()).findByAccountIdAndCurrencyForUpdate(any(), any());
    }

    @Test
    @DisplayName("a currency the account does not hold is rejected")
    void missingBalanceIsRejected() {
        when(accountMapper.existsById(ACCOUNT_ID)).thenReturn(true);
        when(balanceMapper.findByAccountIdAndCurrencyForUpdate(ACCOUNT_ID, Currency.EUR)).thenReturn(null);

        assertThatThrownBy(() -> transactionService.createTransaction(ACCOUNT_ID, request("10.0000", Direction.IN)))
                .isInstanceOf(BalanceNotFoundException.class)
                .hasMessageContaining("EUR");

        verify(transactionMapper, never()).insert(any());
    }

    @Test
    @DisplayName("publishes TRANSACTION_CREATED and BALANCE_UPDATED with both sides of the change")
    void publishesBothEvents() {
        lockedBalance("100.0000");

        transactionService.createTransaction(ACCOUNT_ID, request("10.0000", Direction.IN));

        ArgumentCaptor<TransactionCreatedEvent> txEvent = ArgumentCaptor.forClass(TransactionCreatedEvent.class);
        verify(eventPublisher).publishAfterCommit(eq(EventType.TRANSACTION_CREATED), txEvent.capture());
        assertThat(txEvent.getValue().transactionId()).isEqualTo(500L);
        assertThat(txEvent.getValue().balanceAfter()).isEqualByComparingTo("110.00");

        ArgumentCaptor<BalanceUpdatedEvent> balanceEvent = ArgumentCaptor.forClass(BalanceUpdatedEvent.class);
        verify(eventPublisher).publishAfterCommit(eq(EventType.BALANCE_UPDATED), balanceEvent.capture());
        assertThat(balanceEvent.getValue().previousAmount()).isEqualByComparingTo("100.00");
        assertThat(balanceEvent.getValue().availableAmount()).isEqualByComparingTo("110.00");
        assertThat(balanceEvent.getValue().currency()).isEqualTo(Currency.EUR);
    }

    @Test
    @DisplayName("listing transactions requires the account to exist")
    void listingRequiresKnownAccount() {
        when(accountMapper.existsById(ACCOUNT_ID)).thenReturn(false);

        assertThatThrownBy(() -> transactionService.getTransactions(ACCOUNT_ID))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    @DisplayName("listing maps stored transactions to responses")
    void listingMapsTransactions() {
        Transaction stored = new Transaction();
        stored.setId(1L);
        stored.setAccountId(ACCOUNT_ID);
        stored.setAmount(new BigDecimal("5.0000"));
        stored.setCurrency(Currency.EUR);
        stored.setDirection(Direction.IN);
        stored.setDescription("deposit");
        stored.setBalanceAfter(new BigDecimal("5.0000"));
        when(accountMapper.existsById(ACCOUNT_ID)).thenReturn(true);
        when(transactionMapper.findByAccountId(ACCOUNT_ID)).thenReturn(List.of(stored));

        List<TransactionResponse> responses = transactionService.getTransactions(ACCOUNT_ID);

        assertThat(responses).singleElement().satisfies(response -> {
            assertThat(response.transactionId()).isEqualTo(1L);
            assertThat(response.description()).isEqualTo("deposit");
            assertThat(response.balanceAfter()).isEqualByComparingTo("5.00");
        });
    }

    /** BigDecimal equality is scale-sensitive, so compare numerically. */
    private static BigDecimal argThatEquals(String expected) {
        return org.mockito.ArgumentMatchers.argThat(
                actual -> actual != null && actual.compareTo(new BigDecimal(expected)) == 0);
    }
}
