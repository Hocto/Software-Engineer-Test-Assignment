package com.tuum.banking.service;

import com.tuum.banking.exception.AccountNotFoundException;
import com.tuum.banking.messaging.EventPublisher;
import com.tuum.banking.messaging.event.AccountCreatedEvent;
import com.tuum.banking.messaging.event.EventType;
import com.tuum.banking.model.dto.AccountResponse;
import com.tuum.banking.model.dto.BalanceResponse;
import com.tuum.banking.model.dto.CreateAccountRequest;
import com.tuum.banking.model.entity.Account;
import com.tuum.banking.model.entity.Balance;
import com.tuum.banking.model.enums.Currency;
import com.tuum.banking.repository.AccountMapper;
import com.tuum.banking.repository.BalanceMapper;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountMapper accountMapper;

    @Mock
    private BalanceMapper balanceMapper;

    @Mock
    private EventPublisher eventPublisher;

    @InjectMocks
    private AccountService accountService;

    /** Stands in for the database assigning a generated key on insert. */
    private void stubGeneratedAccountId(long id) {
        doAnswer(invocation -> {
            invocation.getArgument(0, Account.class).setId(id);
            return null;
        }).when(accountMapper).insert(any(Account.class));
    }

    @Test
    @DisplayName("creates one zero balance per requested currency")
    void createsZeroBalancePerCurrency() {
        stubGeneratedAccountId(42L);
        CreateAccountRequest request = new CreateAccountRequest(7L, "EE", List.of(Currency.EUR, Currency.USD));

        AccountResponse response = accountService.createAccount(request);

        assertThat(response.accountId()).isEqualTo(42L);
        assertThat(response.customerId()).isEqualTo(7L);
        assertThat(response.country()).isEqualTo("EE");
        assertThat(response.balances())
                .extracting(BalanceResponse::currency)
                .containsExactly(Currency.EUR, Currency.USD);
        assertThat(response.balances())
                .extracting(BalanceResponse::availableAmount)
                .allSatisfy(amount -> assertThat(amount).isEqualByComparingTo(BigDecimal.ZERO));

        ArgumentCaptor<Balance> balances = ArgumentCaptor.forClass(Balance.class);
        verify(balanceMapper, times(2)).insert(balances.capture());
        assertThat(balances.getAllValues()).allSatisfy(balance -> {
            assertThat(balance.getAccountId()).isEqualTo(42L);
            assertThat(balance.getAvailableAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        });
    }

    @Test
    @DisplayName("collapses repeated currencies into a single balance")
    void deduplicatesCurrencies() {
        stubGeneratedAccountId(1L);
        CreateAccountRequest request =
                new CreateAccountRequest(7L, "EE", List.of(Currency.EUR, Currency.EUR, Currency.GBP));

        AccountResponse response = accountService.createAccount(request);

        assertThat(response.balances())
                .extracting(BalanceResponse::currency)
                .containsExactly(Currency.EUR, Currency.GBP);
        verify(balanceMapper, times(2)).insert(any(Balance.class));
    }

    @Test
    @DisplayName("publishes ACCOUNT_CREATED carrying the new balances")
    void publishesAccountCreatedEvent() {
        stubGeneratedAccountId(99L);
        CreateAccountRequest request = new CreateAccountRequest(3L, "SE", List.of(Currency.SEK));

        accountService.createAccount(request);

        ArgumentCaptor<AccountCreatedEvent> event = ArgumentCaptor.forClass(AccountCreatedEvent.class);
        verify(eventPublisher).publishAfterCommit(eq(EventType.ACCOUNT_CREATED), event.capture());
        assertThat(event.getValue().accountId()).isEqualTo(99L);
        assertThat(event.getValue().customerId()).isEqualTo(3L);
        assertThat(event.getValue().country()).isEqualTo("SE");
        assertThat(event.getValue().balances()).singleElement()
                .satisfies(balance -> assertThat(balance.currency()).isEqualTo(Currency.SEK));
    }

    @Test
    @DisplayName("returns the account with its balances")
    void getAccountReturnsBalances() {
        Account account = new Account(5L, "GB");
        account.setId(11L);
        Balance balance = new Balance(11L, Currency.GBP, new BigDecimal("150.00"));
        when(accountMapper.findById(11L)).thenReturn(account);
        when(balanceMapper.findByAccountId(11L)).thenReturn(List.of(balance));

        AccountResponse response = accountService.getAccount(11L);

        assertThat(response.accountId()).isEqualTo(11L);
        assertThat(response.balances()).singleElement().satisfies(b -> {
            assertThat(b.currency()).isEqualTo(Currency.GBP);
            assertThat(b.availableAmount()).isEqualByComparingTo("150.00");
        });
    }

    @Test
    @DisplayName("throws AccountNotFoundException for an unknown account")
    void getAccountRejectsUnknownAccount() {
        when(accountMapper.findById(404L)).thenReturn(null);

        assertThatThrownBy(() -> accountService.getAccount(404L))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessageContaining("404");

        verify(balanceMapper, never()).findByAccountId(any());
    }
}
