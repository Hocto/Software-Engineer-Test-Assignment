package com.tuum.banking.service;

import com.tuum.banking.exception.AccountNotFoundException;
import com.tuum.banking.messaging.EventPublisher;
import com.tuum.banking.messaging.event.AccountCreatedEvent;
import com.tuum.banking.messaging.event.EventType;
import com.tuum.banking.model.dto.AccountResponse;
import com.tuum.banking.model.dto.BalanceResponse;
import com.tuum.banking.model.dto.CreateAccountRequest;
import com.tuum.banking.model.Money;
import com.tuum.banking.model.entity.Account;
import com.tuum.banking.model.entity.Balance;
import com.tuum.banking.model.enums.Currency;
import com.tuum.banking.repository.AccountMapper;
import com.tuum.banking.repository.BalanceMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class AccountService {

    private final AccountMapper accountMapper;
    private final BalanceMapper balanceMapper;
    private final EventPublisher eventPublisher;

    public AccountService(AccountMapper accountMapper, BalanceMapper balanceMapper, EventPublisher eventPublisher) {
        this.accountMapper = accountMapper;
        this.balanceMapper = balanceMapper;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Creates the account and one zero balance per requested currency, atomically.
     *
     * <p>Repeated currencies are collapsed rather than rejected: the request's intent is
     * a set of currencies, and the outcome — exactly one balance per named currency —
     * is the same either way. Insertion order is preserved.
     */
    @Transactional
    public AccountResponse createAccount(CreateAccountRequest request) {
        Account account = new Account(request.customerId(), request.country());
        accountMapper.insert(account);

        Set<Currency> currencies = new LinkedHashSet<>(request.currencies());
        List<Balance> balances = currencies.stream()
                .map(currency -> {
                    Balance balance = new Balance(account.getId(), currency, Money.ZERO);
                    balanceMapper.insert(balance);
                    return balance;
                })
                .toList();

        AccountResponse response = AccountResponse.from(account, balances);
        eventPublisher.publishAfterCommit(EventType.ACCOUNT_CREATED, new AccountCreatedEvent(
                response.accountId(), response.customerId(), response.country(),
                balances.stream().map(BalanceResponse::from).toList()));

        return response;
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccount(Long accountId) {
        Account account = accountMapper.findById(accountId);
        if (account == null) {
            throw new AccountNotFoundException(accountId);
        }
        return AccountResponse.from(account, balanceMapper.findByAccountId(accountId));
    }
}
