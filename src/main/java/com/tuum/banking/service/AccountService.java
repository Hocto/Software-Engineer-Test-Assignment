package com.tuum.banking.service;

import com.tuum.banking.exception.AccountNotFoundException;
import com.tuum.banking.messaging.DomainEventPublisher;
import com.tuum.banking.messaging.event.AccountCreatedEvent;
import com.tuum.banking.messaging.event.EventType;
import com.tuum.banking.model.Money;
import com.tuum.banking.model.dto.AccountResponse;
import com.tuum.banking.model.dto.CreateAccountRequest;
import com.tuum.banking.model.entity.Account;
import com.tuum.banking.model.entity.Balance;
import com.tuum.banking.model.enums.Currency;
import com.tuum.banking.repository.AccountMapper;
import com.tuum.banking.repository.BalanceMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;

@Service
public class AccountService {

    private final AccountMapper accountMapper;
    private final BalanceMapper balanceMapper;
    private final DomainEventPublisher eventPublisher;

    public AccountService(AccountMapper accountMapper, BalanceMapper balanceMapper,
                          DomainEventPublisher eventPublisher) {
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

        // LinkedHashSet both de-duplicates and preserves the requested order. Building the
        // list first and inserting once keeps account creation to two round trips whatever
        // the currency count. Nothing downstream reads the generated balance ids today —
        // the response and the event both carry only amount and currency — but the mapper
        // writes them back regardless, so that stays true if either shape gains an id.
        List<Balance> balances = new LinkedHashSet<>(request.currencies()).stream()
                .map(currency -> new Balance(account.getId(), currency, Money.ZERO))
                .toList();
        balanceMapper.insertAll(balances);

        eventPublisher.publishAfterCommit(EventType.ACCOUNT_CREATED,
                AccountCreatedEvent.from(account, balances));

        return AccountResponse.from(account, balances);
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
